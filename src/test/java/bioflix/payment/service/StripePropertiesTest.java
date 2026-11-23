package bioflix.payment.service;

import bioflix.payment.stripe.StripeProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class StripePropertiesTest {

    @Test
    void checkoutLinksAreValidAtLeast30MinutesBecauseStripeRequiresIt() {
        assertThat(props(Duration.ofMinutes(15)).checkoutValidFor()).isEqualTo(Duration.ofMinutes(30));
        assertThat(props(null).checkoutValidFor()).isEqualTo(Duration.ofMinutes(30));
        assertThat(props(Duration.ofHours(1)).checkoutValidFor()).isEqualTo(Duration.ofHours(1));
    }

    private static StripeProperties props(Duration validFor) {
        return new StripeProperties(true, "sk_test_x", "whsec_x", "http://ok", "http://cancel", validFor);
    }
}
