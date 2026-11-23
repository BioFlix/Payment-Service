package bioflix.payment.messaging;

import bioflix.payment.inbox.ProcessedEvent;
import bioflix.payment.inbox.ProcessedEventRepository;
import bioflix.payment.messaging.BookingEvents.BookingCancelled;
import bioflix.payment.messaging.BookingEvents.BookingCreated;
import bioflix.payment.service.PaymentService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Consumes booking.created and booking.cancelled from payment-service.booking-events.
 * <ul>
 *   <li>Idempotent: the eventId is saved in processed_events in the same transaction as the payment change,
 *       so a redelivered event is skipped.</li>
 *   <li>Invalid messages (bad JSON, missing ids) are rejected and go to the dead-letter queue.</li>
 *   <li>Other exceptions (database or Stripe down) are retried, then dead-lettered.</li>
 * </ul>
 * The message is parsed by hand, so it works whatever content type the producer sets.
 */
@Component
public class BookingEventListener {

    private static final Logger log = LoggerFactory.getLogger(BookingEventListener.class);

    private final PaymentService paymentService;
    private final ProcessedEventRepository processedEvents;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public BookingEventListener(PaymentService paymentService, ProcessedEventRepository processedEvents,
                                JsonMapper jsonMapper, Clock clock) {
        this.paymentService = paymentService;
        this.processedEvents = processedEvents;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @RabbitListener(queues = RabbitConfig.BOOKING_QUEUE)
    @Transactional
    public void onMessage(Message message) {
        IncomingEvent event = parse(message);

        if (processedEvents.existsById(event.eventId())) {
            log.info("Event {} ({}) already processed – skipped", event.eventId(), event.type());
            return;
        }

        switch (event.type()) {
            case EventTypes.BOOKING_CREATED -> {
                BookingCreated data = data(event, BookingCreated.class);
                if (data.amount() == null || data.amount() < 0 || data.currency() == null) {
                    throw new AmqpRejectAndDontRequeueException("booking.created without a valid amount/currency: "
                            + event.eventId());
                }
                paymentService.handleBookingCreated(uuid(data.bookingId(), "bookingId", event),
                        uuid(data.userId(), "userId", event), data.amount(), data.currency(), data.expiresAt());
            }
            case EventTypes.BOOKING_CANCELLED -> {
                BookingCancelled data = data(event, BookingCancelled.class);
                paymentService.handleBookingCancelled(uuid(data.bookingId(), "bookingId", event),
                        Boolean.TRUE.equals(data.wasPaid()), data.reason());
            }
            default -> log.warn("Unexpected event type {} on {} – ignored", event.type(), RabbitConfig.BOOKING_QUEUE);
        }

        processedEvents.save(new ProcessedEvent(event.eventId(), event.type(), clock.instant()));
    }

    /** The envelope as it arrives; data is kept as a JSON tree until we know the type. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IncomingEvent(UUID eventId, String type, Integer version, Instant occurredAt, String producer,
                                String correlationId, JsonNode data) {
    }

    private IncomingEvent parse(Message message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        IncomingEvent event;
        try {
            event = jsonMapper.readValue(body, IncomingEvent.class);
        } catch (JacksonException ex) {
            throw new AmqpRejectAndDontRequeueException("Unreadable event: " + ex.getOriginalMessage(), ex);
        }
        if (event == null || event.eventId() == null || event.type() == null || event.data() == null
                || !event.data().isObject()) {
            throw new AmqpRejectAndDontRequeueException("Event without eventId/type/data: " + body);
        }
        return event;
    }

    private <T> T data(IncomingEvent event, Class<T> type) {
        try {
            return jsonMapper.treeToValue(event.data(), type);
        } catch (JacksonException ex) {
            throw new AmqpRejectAndDontRequeueException("Invalid data in " + event.type() + " " + event.eventId(), ex);
        }
    }

    private static UUID uuid(String value, String field, IncomingEvent event) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException ex) {
            throw new AmqpRejectAndDontRequeueException(
                    "Invalid " + field + " '" + value + "' in " + event.type() + " " + event.eventId(), ex);
        }
    }
}
