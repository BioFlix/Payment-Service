package bioflix.payment.stripe;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Bound from the "stripe.*" section in application.yml.
 * Never commit real keys – use environment variables (STRIPE_SECRET_KEY, STRIPE_WEBHOOK_SECRET).
 *
 * @param checkoutValidFor how long a Checkout link is valid; Stripe requires 30 min – 24 h
 */
@ConfigurationProperties(prefix = "stripe")
public record StripeProperties(
        boolean enabled,
        String secretKey,
        String webhookSecret,
        String successUrl,
        String cancelUrl,
        Duration checkoutValidFor
) {
    private static final Duration STRIPE_MINIMUM = Duration.ofMinutes(30);

    public StripeProperties {
        if (checkoutValidFor == null || checkoutValidFor.compareTo(STRIPE_MINIMUM) < 0) {
            checkoutValidFor = STRIPE_MINIMUM;
        }
    }
}
