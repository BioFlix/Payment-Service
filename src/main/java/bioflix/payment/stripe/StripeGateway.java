package bioflix.payment.stripe;

import bioflix.payment.entity.Payment;
import com.stripe.Stripe;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentRetrieveParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * All calls to Stripe live here (the red "HTTP: charge / refund" arrow in the diagram), so the rest of the
 * service only sees our own small records. With stripe.enabled=false (dev mode) nothing is sent to Stripe.
 */
@Component
public class StripeGateway {

    private static final Logger log = LoggerFactory.getLogger(StripeGateway.class);

    private final StripeProperties props;

    public StripeGateway(StripeProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        if (!props.enabled()) {
            log.warn("Stripe is DISABLED (dev mode). Use POST /api/payments/dev/{bookingId}/succeed|fail to simulate.");
            return;
        }
        if (isBlank(props.secretKey()) || isBlank(props.webhookSecret())) {
            throw new IllegalStateException("Stripe is enabled but STRIPE_SECRET_KEY or STRIPE_WEBHOOK_SECRET is missing. "
                    + "Set them (test keys from dashboard.stripe.com + `stripe listen`) or run with STRIPE_ENABLED=false.");
        }
        if (!props.secretKey().startsWith("sk_test_") && !props.secretKey().startsWith("rk_test_")) {
            log.warn("The Stripe key is not a TEST key – real money will be charged!");
        }
        Stripe.apiKey = props.secretKey();
    }

    public boolean isEnabled() {
        return props.enabled();
    }

    // ------------------------------------------------------------------ records

    public record CheckoutSession(String id, String url, Instant expiresAt) {
    }

    /** What we need from a Stripe webhook event. bookingId comes from the session metadata. */
    public record WebhookEvent(String eventId, String type, String sessionId, String paymentStatus,
                               String paymentIntentId, String bookingId) {
        public boolean isCheckoutSession() {
            return sessionId != null;
        }
    }

    public static class InvalidSignatureException extends RuntimeException {
        public InvalidSignatureException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ checkout

    /**
     * Creates a Stripe hosted Checkout page. bookingId and paymentId go in metadata so the webhook can find the
     * payment again. The link is valid for stripe.checkout-valid-for (at least 30 minutes – Stripe's minimum).
     */
    public CheckoutSession createCheckoutSession(Payment payment, String description, Instant now) throws StripeException {
        Instant expiresAt = now.plus(props.checkoutValidFor());
        if (!props.enabled()) {
            return new CheckoutSession("dev_cs_" + UUID.randomUUID(),
                    "http://localhost:8084/api/payments/dev/" + payment.getBookingId(), expiresAt);
        }

        String bookingId = payment.getBookingId().toString();
        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(props.successUrl().replace("{BOOKING_ID}", bookingId))
                .setCancelUrl(props.cancelUrl().replace("{BOOKING_ID}", bookingId))
                .setClientReferenceId(bookingId)
                .setExpiresAt(expiresAt.getEpochSecond())
                .putMetadata("bookingId", bookingId)
                .putMetadata("paymentId", payment.getId().toString())
                .setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder()
                        .putMetadata("bookingId", bookingId)
                        .putMetadata("paymentId", payment.getId().toString())
                        .build())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency(payment.getCurrency().toLowerCase(Locale.ROOT))
                                .setUnitAmount((long) payment.getAmount())
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                        .setName(description)
                                        .build())
                                .build())
                        .build())
                .build();

        // Same key for the same payment + expiry: a retried request returns the same session instead of a new one
        RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey("checkout-" + payment.getId() + "-" + expiresAt.getEpochSecond())
                .build();
        Session session = Session.create(params, options);
        return new CheckoutSession(session.getId(), session.getUrl(), expiresAt);
    }

    /**
     * Closes an open Checkout page so the customer can't pay for a cancelled booking.
     * Returns false if Stripe says it can't be expired (already completed or expired) – not an error.
     */
    public boolean expireCheckoutSession(String sessionId) throws StripeException {
        if (!props.enabled() || sessionId == null) {
            return true;
        }
        try {
            Session.retrieve(sessionId).expire();
            return true;
        } catch (InvalidRequestException ex) {
            log.info("Stripe could not expire checkout session {}: {}", sessionId, ex.getMessage());
            return false;
        }
    }

    /** Stripe's receipt page for the charge, or null if it can't be found (not important enough to fail on). */
    public String receiptUrl(String paymentIntentId) {
        if (!props.enabled() || paymentIntentId == null) {
            return null;
        }
        try {
            PaymentIntent intent = PaymentIntent.retrieve(paymentIntentId,
                    PaymentIntentRetrieveParams.builder().addExpand("latest_charge").build(), null);
            Charge charge = intent.getLatestChargeObject();
            return charge == null ? null : charge.getReceiptUrl();
        } catch (StripeException | RuntimeException ex) {
            log.warn("Could not fetch receipt url for {}: {}", paymentIntentId, ex.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ refund

    /**
     * Full refund. The idempotency key makes a retry (e.g. after our transaction rolled back) return the same
     * refund instead of refunding twice. Returns Stripe's refund id.
     */
    public String refund(Payment payment) throws StripeException {
        if (!props.enabled()) {
            log.info("[dev] Pretending to refund payment {}", payment.getId());
            return "dev_re_" + payment.getId();
        }
        RefundCreateParams params = RefundCreateParams.builder()
                .setPaymentIntent(payment.getStripePaymentIntent())
                .putMetadata("bookingId", payment.getBookingId().toString())
                .putMetadata("paymentId", payment.getId().toString())
                .build();
        RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey("refund-" + payment.getId())
                .build();
        return com.stripe.model.Refund.create(params, options).getId();
    }

    // ------------------------------------------------------------------ webhooks

    /** Verifies the Stripe-Signature header and extracts what we need. */
    public WebhookEvent parseWebhook(String payload, String signature) {
        Event event;
        try {
            event = Webhook.constructEvent(payload, signature, props.webhookSecret());
        } catch (SignatureVerificationException ex) {
            throw new InvalidSignatureException("Invalid Stripe signature");
        }

        StripeObject object = dataObject(event);
        if (object instanceof Session session) {
            String bookingId = session.getMetadata() == null ? null : session.getMetadata().get("bookingId");
            return new WebhookEvent(event.getId(), event.getType(), session.getId(), session.getPaymentStatus(),
                    session.getPaymentIntent(), bookingId);
        }
        return new WebhookEvent(event.getId(), event.getType(), null, null, null, null);
    }

    /** The event's object. Falls back to unsafe deserialization if the event's API version differs from the SDK's. */
    private static StripeObject dataObject(Event event) {
        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
        if (deserializer.getObject().isPresent()) {
            return deserializer.getObject().get();
        }
        try {
            return deserializer.deserializeUnsafe();
        } catch (Exception ex) {
            log.warn("Could not read Stripe event {} ({}): {}", event.getId(), event.getType(), ex.getMessage());
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
