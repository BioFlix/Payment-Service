package bioflix.payment.integration;

import bioflix.payment.dto.PaymentResponse;
import bioflix.payment.entity.PaymentStatus;
import bioflix.payment.exception.NotFoundException;
import bioflix.payment.messaging.RabbitConfig;
import bioflix.payment.stripe.StripeGateway;
import bioflix.payment.stripe.StripeGateway.CheckoutSession;
import bioflix.payment.stripe.StripeGateway.WebhookEvent;
import bioflix.payment.stripe.StripeWebhookService;
import bioflix.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Real Postgres (Flyway schema) + RabbitMQ in Docker; Stripe is mocked (StripeGateway), so no Stripe account is
 * needed. Booking events are sent as raw JSON envelopes exactly as booking-service sends them, and the payment.*
 * events we publish are read from a test queue bound to cinema.events – so this also checks the event contract.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "stripe.enabled=false",
        "spring.rabbitmq.listener.simple.retry.initial-interval=100ms",
        "spring.rabbitmq.listener.simple.retry.max-attempts=2",
        "app.outbox.poll-interval=200ms"
})
class PaymentFlowIntegrationTest {

    private static final String EVENTS_TEST_QUEUE = "test.payment-events";
    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final UUID ALICE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID BOB = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final MutableClock CLOCK = new MutableClock();

    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3-management");

    @TestBean Clock clock;
    @MockitoBean StripeGateway stripe;

    @Autowired PaymentService paymentService;
    @Autowired StripeWebhookService webhookService;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired AmqpAdmin amqpAdmin;
    @Autowired TopicExchange eventsExchange;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper jsonMapper;

    private int sessionCounter;

    static Clock clock() {
        return CLOCK;
    }

    @BeforeEach
    void setUp() throws Exception {
        CLOCK.reset();
        jdbc.execute("TRUNCATE refunds, payments, stripe_webhook_events, outbox_events, processed_events");

        Queue events = new Queue(EVENTS_TEST_QUEUE, false, false, false);
        amqpAdmin.declareQueue(events);
        amqpAdmin.declareBinding(BindingBuilder.bind(events).to(eventsExchange).with("payment.#"));
        amqpAdmin.purgeQueue(EVENTS_TEST_QUEUE, false);

        reset(stripe);
        when(stripe.isEnabled()).thenReturn(true);
        when(stripe.createCheckoutSession(any(), anyString(), any())).thenAnswer(inv -> {
            Instant now = inv.getArgument(2);
            sessionCounter++;
            return new CheckoutSession("cs_test_" + sessionCounter, "https://checkout.stripe.com/c/pay/cs_test_" + sessionCounter,
                    now.plus(Duration.ofMinutes(30)));
        });
        when(stripe.expireCheckoutSession(anyString())).thenReturn(true);
        when(stripe.receiptUrl(anyString())).thenReturn("https://pay.stripe.com/receipts/test");
        when(stripe.refund(any())).thenReturn("re_test_1");
    }

    // ------------------------------------------------------------------ booking.created + checkout

    @Test
    void bookingCreatedMakesOnePendingPaymentEvenIfSentTwice() {
        UUID bookingId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        sendBookingCreated(eventId, bookingId, ALICE, 27000);
        sendBookingCreated(eventId, bookingId, ALICE, 27000);               // redelivery
        sendBookingCreated(UUID.randomUUID(), bookingId, ALICE, 27000);     // same booking, new event id

        await().atMost(WAIT).until(() -> count("processed_events") == 2);
        assertThat(count("payments")).isEqualTo(1);
        assertThat(status(bookingId)).isEqualTo(PaymentStatus.PENDING);
        assertThat(jdbc.queryForObject("select amount from payments where booking_id = ?", Integer.class, bookingId))
                .isEqualTo(27000);
    }

    @Test
    void checkoutLinkIsCreatedOnceAndOnlyShownToTheOwner() throws Exception {
        UUID bookingId = pendingPayment(ALICE, 13500);

        PaymentResponse first = paymentService.getOrCreateCheckout(bookingId, ALICE, false);
        PaymentResponse again = paymentService.getOrCreateCheckout(bookingId, ALICE, false);

        assertThat(first.checkoutUrl()).isEqualTo("https://checkout.stripe.com/c/pay/cs_test_1");
        assertThat(first.checkoutExpiresAt()).isEqualTo(CLOCK.instant().plus(Duration.ofMinutes(30)));
        assertThat(again.checkoutUrl()).isEqualTo(first.checkoutUrl());
        verify(stripe, times(1)).createCheckoutSession(any(), anyString(), any());

        assertThatThrownBy(() -> paymentService.getOrCreateCheckout(bookingId, BOB, false))
                .isInstanceOf(NotFoundException.class);
        assertThat(paymentService.getOrCreateCheckout(bookingId, BOB, true).bookingId()).isEqualTo(bookingId);
    }

    // ------------------------------------------------------------------ Stripe webhooks

    @Test
    void paidCheckoutCompletesAndPublishesPaymentCompletedOnce() {
        UUID bookingId = pendingPayment(ALICE, 13500);
        paymentService.getOrCreateCheckout(bookingId, ALICE, false);

        WebhookEvent paid = webhook("evt_1", "checkout.session.completed", "cs_test_1", "paid", "pi_1", bookingId);
        stripeSends(paid);
        stripeSends(paid);   // Stripe retries the same event

        assertThat(status(bookingId)).isEqualTo(PaymentStatus.COMPLETED);
        JsonNode event = nextEvent("payment.completed");
        assertThat(event.get("correlationId").asString()).isEqualTo(bookingId.toString());
        assertThat(event.get("producer").asString()).isEqualTo("payment-service");
        JsonNode data = event.get("data");
        assertThat(data.get("paymentId").asString()).isNotBlank();
        assertThat(data.get("bookingId").asString()).isEqualTo(bookingId.toString());
        assertThat(data.get("userId").asString()).isEqualTo(ALICE.toString());
        assertThat(data.get("amount").asInt()).isEqualTo(13500);
        assertThat(data.get("currency").asString()).isEqualTo("DKK");
        assertThat(data.get("receiptNumber").asString()).startsWith("BF-");
        assertThat(data.get("receiptUrl").asString()).isEqualTo("https://pay.stripe.com/receipts/test");
        assertThat(rabbitTemplate.receive(EVENTS_TEST_QUEUE, 1_000)).isNull();
    }

    @Test
    void expiredCurrentCheckoutFailsButAnOldLinkIsIgnored() {
        UUID bookingId = pendingPayment(ALICE, 13500);
        paymentService.getOrCreateCheckout(bookingId, ALICE, false);              // cs_test_1

        stripeSends(webhook("evt_old", "checkout.session.expired", "cs_test_0", null, null, bookingId));
        assertThat(status(bookingId)).isEqualTo(PaymentStatus.PENDING);

        stripeSends(webhook("evt_exp", "checkout.session.expired", "cs_test_1", null, null, bookingId));
        assertThat(status(bookingId)).isEqualTo(PaymentStatus.FAILED);
        JsonNode data = nextEvent("payment.failed").get("data");
        assertThat(data.get("bookingId").asString()).isEqualTo(bookingId.toString());
        assertThat(data.get("reason").asString()).isEqualTo("Checkout expired");
    }

    // ------------------------------------------------------------------ booking.cancelled

    @Test
    void bookingCancelledBeforePaymentClosesTheCheckout() throws Exception {
        UUID bookingId = pendingPayment(ALICE, 13500);
        paymentService.getOrCreateCheckout(bookingId, ALICE, false);

        sendBookingCancelled(UUID.randomUUID(), bookingId, "EXPIRED", false);

        await().atMost(WAIT).until(() -> status(bookingId) == PaymentStatus.CANCELLED);
        verify(stripe).expireCheckoutSession("cs_test_1");
        verify(stripe, never()).refund(any());
        assertThat(paymentService.getOrCreateCheckout(bookingId, ALICE, false).checkoutUrl()).isNull();
    }

    @Test
    void paidBookingThatIsCancelledIsRefundedOnce() throws Exception {
        UUID bookingId = pendingPayment(ALICE, 13500);
        paymentService.getOrCreateCheckout(bookingId, ALICE, false);
        stripeSends(webhook("evt_1", "checkout.session.completed", "cs_test_1", "paid", "pi_1", bookingId));
        nextEvent("payment.completed");

        sendBookingCancelled(UUID.randomUUID(), bookingId, "USER", true);
        await().atMost(WAIT).until(() -> status(bookingId) == PaymentStatus.REFUNDED);
        sendBookingCancelled(UUID.randomUUID(), bookingId, "USER", true);   // a second cancel must not refund again
        await().atMost(WAIT).until(() -> count("processed_events") == 2);

        verify(stripe, times(1)).refund(any());
        assertThat(jdbc.queryForObject("select reason from refunds", String.class)).isEqualTo("BOOKING_CANCELLED");
        JsonNode data = nextEvent("payment.refunded").get("data");
        assertThat(data.get("refundId").asString()).isNotBlank();
        assertThat(data.get("amount").asInt()).isEqualTo(13500);
        assertThat(rabbitTemplate.receive(EVENTS_TEST_QUEUE, 1_000)).isNull();
    }

    /** The hole: the booking expired/was cancelled, then Stripe reports the money anyway → refund at once. */
    @Test
    void paymentAfterBookingWasCancelledIsRefundedImmediately() throws Exception {
        UUID bookingId = pendingPayment(ALICE, 13500);
        paymentService.getOrCreateCheckout(bookingId, ALICE, false);
        when(stripe.expireCheckoutSession("cs_test_1")).thenReturn(false);   // too late – the customer already paid

        sendBookingCancelled(UUID.randomUUID(), bookingId, "EXPIRED", false);
        await().atMost(WAIT).until(() -> status(bookingId) == PaymentStatus.CANCELLED);

        stripeSends(webhook("evt_late", "checkout.session.completed", "cs_test_1", "paid", "pi_late", bookingId));

        assertThat(status(bookingId)).isEqualTo(PaymentStatus.REFUNDED);
        verify(stripe, times(1)).refund(any());
        assertThat(jdbc.queryForObject("select reason from refunds", String.class)).isEqualTo("PAID_AFTER_CANCEL");
        assertThat(jdbc.queryForObject("select stripe_payment_intent from payments where booking_id = ?",
                String.class, bookingId)).isEqualTo("pi_late");
        // booking is already cancelled, so no payment.completed – only payment.refunded (for the mail)
        nextEvent("payment.refunded");
        assertThat(rabbitTemplate.receive(EVENTS_TEST_QUEUE, 1_000)).isNull();
    }

    @Test
    void invalidBookingEventGoesToTheDeadLetterQueue() {
        amqpAdmin.purgeQueue(RabbitConfig.DEAD_LETTER_QUEUE, false);

        sendEvent("booking.created", UUID.randomUUID(), UUID.randomUUID(), Map.of("bookingId", "not-a-uuid"));

        await().atMost(WAIT).until(() -> rabbitTemplate.receive(RabbitConfig.DEAD_LETTER_QUEUE, 200) != null);
        assertThat(count("payments")).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private UUID pendingPayment(UUID user, int amount) {
        UUID bookingId = UUID.randomUUID();
        sendBookingCreated(UUID.randomUUID(), bookingId, user, amount);
        await().atMost(WAIT).until(() -> jdbc.queryForObject(
                "select count(*) from payments where booking_id = ?", Long.class, bookingId) == 1);
        return bookingId;
    }

    private void stripeSends(WebhookEvent event) {
        when(stripe.parseWebhook(eq("payload-" + event.eventId()), eq("sig"))).thenReturn(event);
        webhookService.handle("payload-" + event.eventId(), "sig");
    }

    private static WebhookEvent webhook(String id, String type, String sessionId, String paymentStatus,
                                        String paymentIntent, UUID bookingId) {
        return new WebhookEvent(id, type, sessionId, paymentStatus, paymentIntent, bookingId.toString());
    }

    private PaymentStatus status(UUID bookingId) {
        return PaymentStatus.valueOf(jdbc.queryForObject("select status from payments where booking_id = ?",
                String.class, bookingId));
    }

    private long count(String table) {
        Long n = jdbc.queryForObject("select count(*) from " + table, Long.class);
        return n == null ? 0 : n;
    }

    /** Waits for the next payment.* event on the test queue and checks its type and routing key. */
    private JsonNode nextEvent(String expectedType) {
        Message message = rabbitTemplate.receive(EVENTS_TEST_QUEUE, WAIT.toMillis());
        assertThat(message).as("expected a %s event", expectedType).isNotNull();
        assertThat(message.getMessageProperties().getReceivedRoutingKey()).isEqualTo(expectedType);
        JsonNode event = jsonMapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
        assertThat(event.get("type").asString()).isEqualTo(expectedType);
        return event;
    }

    private void sendBookingCreated(UUID eventId, UUID bookingId, UUID userId, int amount) {
        Map<String, Object> data = new HashMap<>();
        data.put("bookingId", bookingId.toString());
        data.put("userId", userId.toString());
        data.put("showtimeId", "66f000000000000000000c01");
        data.put("amount", amount);
        data.put("currency", "DKK");
        data.put("seats", java.util.List.of(Map.of("row", "F", "number", 7, "type", "STANDARD", "price", amount)));
        data.put("expiresAt", CLOCK.instant().plus(Duration.ofMinutes(15)).toString());
        sendEvent("booking.created", eventId, bookingId, data);
    }

    private void sendBookingCancelled(UUID eventId, UUID bookingId, String reason, boolean wasPaid) {
        sendEvent("booking.cancelled", eventId, bookingId, Map.of(
                "bookingId", bookingId.toString(),
                "userId", ALICE.toString(),
                "reason", reason,
                "wasPaid", wasPaid));
    }

    private void sendEvent(String type, UUID eventId, UUID correlationId, Map<String, Object> data) {
        Map<String, Object> envelope = Map.of(
                "eventId", eventId.toString(),
                "type", type,
                "version", 1,
                "occurredAt", Instant.now().toString(),
                "producer", "booking-service",
                "correlationId", correlationId.toString(),
                "data", data);
        Message message = MessageBuilder.withBody(jsonMapper.writeValueAsBytes(envelope))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();
        rabbitTemplate.send(eventsExchange.getName(), type, message);
    }
}
