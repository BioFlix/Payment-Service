package bioflix.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from the "stripe.*" section in application.yml.
 * Never commit real keys – use environment variables (STRIPE_SECRET_KEY, ...).
 */
@ConfigurationProperties(prefix = "stripe")
public record StripeProperties(
        boolean enabled,
        String secretKey,
        String webhookSecret,
        String successUrl,
        String cancelUrl
) {
}
