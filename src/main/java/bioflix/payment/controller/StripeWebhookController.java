package bioflix.payment.controller;

import bioflix.payment.exception.NotFoundException;
import bioflix.payment.stripe.StripeGateway;
import bioflix.payment.stripe.StripeWebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stripe calls this endpoint (the "Stripe webhooks" arrow in the diagram).
 * 200 = handled (or a duplicate); 400 = bad signature; 5xx = Stripe retries later.
 *
 * Local testing with the Stripe CLI:
 *   stripe listen --forward-to localhost:8084/api/payments/webhook/stripe
 * and put the printed whsec_... in STRIPE_WEBHOOK_SECRET.
 * Events needed: checkout.session.completed, checkout.session.expired,
 * checkout.session.async_payment_succeeded, checkout.session.async_payment_failed.
 */
@RestController
@RequestMapping("/api/payments/webhook")
public class StripeWebhookController {

    private final StripeWebhookService webhookService;
    private final StripeGateway stripeGateway;

    public StripeWebhookController(StripeWebhookService webhookService, StripeGateway stripeGateway) {
        this.webhookService = webhookService;
        this.stripeGateway = stripeGateway;
    }

    @PostMapping("/stripe")
    public ResponseEntity<String> handle(@RequestBody String payload,
                                         @RequestHeader("Stripe-Signature") String signature) {
        if (!stripeGateway.isEnabled()) {
            throw new NotFoundException("Stripe is disabled (dev mode)");
        }
        webhookService.handle(payload, signature);
        return ResponseEntity.ok("ok");
    }
}
