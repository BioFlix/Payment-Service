package bioflix.payment.stripe;

import bioflix.payment.entity.Payment;
import bioflix.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/**
 * Handles one verified Stripe webhook. Each Stripe event id is handled once (stripe_webhook_events), in the same
 * transaction as the payment change: if anything fails, nothing is saved, we answer 500 and Stripe retries.
 */
@Service
public class StripeWebhookService {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookService.class);

    private final StripeGateway stripe;
    private final PaymentService paymentService;
    private final StripeWebhookEventRepository handledEvents;
    private final Clock clock;

    public StripeWebhookService(StripeGateway stripe, PaymentService paymentService,
                                StripeWebhookEventRepository handledEvents, Clock clock) {
        this.stripe = stripe;
        this.paymentService = paymentService;
        this.handledEvents = handledEvents;
        this.clock = clock;
    }

    /** @throws StripeGateway.InvalidSignatureException if the request isn't signed by Stripe */
    @Transactional
    public void handle(String payload, String signature) {
        StripeGateway.WebhookEvent event = stripe.parseWebhook(payload, signature);

        if (handledEvents.existsById(event.eventId())) {
            log.info("Stripe event {} ({}) already handled – skipped", event.eventId(), event.type());
            return;
        }
        handledEvents.save(new StripeWebhookEvent(event.eventId(), event.type(), clock.instant()));

        if (!event.isCheckoutSession()) {
            log.info("Ignoring Stripe event {} ({})", event.eventId(), event.type());
            return;
        }

        Optional<Payment> found = paymentService.findForStripeSession(event.sessionId(), event.bookingId());
        if (found.isEmpty()) {
            log.warn("Stripe event {} ({}) for unknown checkout session {} – ignored",
                    event.eventId(), event.type(), event.sessionId());
            return;
        }
        Payment payment = found.get();

        switch (event.type()) {
            case "checkout.session.completed" -> {
                if ("paid".equals(event.paymentStatus()) || "no_payment_required".equals(event.paymentStatus())) {
                    paymentService.handlePaid(payment.getId(), event.paymentIntentId());
                } else {
                    // e.g. bank transfer: the money comes later as checkout.session.async_payment_succeeded
                    log.info("Checkout {} completed but payment_status={} – waiting for the async result",
                            event.sessionId(), event.paymentStatus());
                }
            }
            case "checkout.session.async_payment_succeeded" ->
                    paymentService.handlePaid(payment.getId(), event.paymentIntentId());
            case "checkout.session.async_payment_failed" ->
                    paymentService.handleFailed(payment.getId(), "Payment failed");
            case "checkout.session.expired" -> {
                // Only the CURRENT link counts – an old link may expire after a new one was created
                if (event.sessionId().equals(payment.getStripeSessionId())) {
                    paymentService.handleFailed(payment.getId(), "Checkout expired");
                } else {
                    log.info("Old checkout session {} for booking {} expired – ignored",
                            event.sessionId(), payment.getBookingId());
                }
            }
            default -> log.info("Unhandled Stripe event type {}", event.type());
        }
    }
}
