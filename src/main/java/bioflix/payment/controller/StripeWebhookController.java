package bioflix.payment.controller;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import bioflix.payment.config.StripeProperties;
import bioflix.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stripe calls this endpoint (the "Stripe webhooks" arrow in the diagram).
 *
 * Local testing with the Stripe CLI:
 *   stripe listen --forward-to localhost:8084/api/payments/webhook/stripe
 * and put the printed whsec_... in STRIPE_WEBHOOK_SECRET.
 */
@RestController
@RequestMapping("/api/payments/webhook")
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final PaymentService paymentService;
    private final StripeProperties props;

    public StripeWebhookController(PaymentService paymentService, StripeProperties props) {
        this.paymentService = paymentService;
        this.props = props;
    }

    @PostMapping("/stripe")
    public ResponseEntity<String> handle(@RequestBody String payload,
                                         @RequestHeader("Stripe-Signature") String signature) {
        Event event;
        try {
            // Verifies that the request really comes from Stripe
            event = Webhook.constructEvent(payload, signature, props.webhookSecret());
        } catch (SignatureVerificationException ex) {
            log.warn("Invalid Stripe signature");
            return ResponseEntity.badRequest().body("Invalid signature");
        }

        StripeObject object = event.getDataObjectDeserializer().getObject().orElse(null);
        if (!(object instanceof Session session)) {
            log.info("Ignoring Stripe event {} ({})", event.getId(), event.getType());
            return ResponseEntity.ok("ignored");
        }

        switch (event.getType()) {
            case "checkout.session.completed" ->
                    paymentService.handleCheckoutCompleted(session.getId(), session.getPaymentIntent());
            case "checkout.session.expired" ->
                    paymentService.handleCheckoutFailed(session.getId(), "Checkout expired");
            case "checkout.session.async_payment_failed" ->
                    paymentService.handleCheckoutFailed(session.getId(), "Payment failed");
            default -> log.info("Unhandled Stripe event type {}", event.getType());
        }
        return ResponseEntity.ok("ok");
    }
}
