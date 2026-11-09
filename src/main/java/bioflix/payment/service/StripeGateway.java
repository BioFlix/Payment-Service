package bioflix.payment.service;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Refund;
import com.stripe.model.checkout.Session;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import bioflix.payment.config.StripeProperties;
import bioflix.payment.entity.Payment;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * All calls to Stripe live here (the red "HTTP: charge / refund" arrow in the diagram).
 * With stripe.enabled=false nothing is sent to Stripe – handy for local development.
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
        if (props.enabled()) {
            Stripe.apiKey = props.secretKey();
        } else {
            log.warn("Stripe is DISABLED (dev mode). Use /api/payments/dev/... to simulate payments.");
        }
    }

    public boolean isEnabled() {
        return props.enabled();
    }

    public record CheckoutSession(String id, String url) {
    }

    /** Creates a Stripe hosted checkout page for the payment. */
    public CheckoutSession createCheckoutSession(Payment payment, String description) throws StripeException {
        if (!props.enabled()) {
            String fakeId = "dev_" + UUID.randomUUID();
            return new CheckoutSession(fakeId, "http://localhost:8084/api/payments/dev/" + payment.getBookingId());
        }

        String bookingId = String.valueOf(payment.getBookingId());

        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(props.successUrl().replace("{BOOKING_ID}", bookingId))
                .setCancelUrl(props.cancelUrl().replace("{BOOKING_ID}", bookingId))
                .setClientReferenceId(bookingId)
                .putMetadata("bookingId", bookingId)
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency(payment.getCurrency().toLowerCase())
                                .setUnitAmount(payment.getAmountMinor())
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                        .setName(description)
                                        .build())
                                .build())
                        .build())
                .build();

        Session session = Session.create(params);
        return new CheckoutSession(session.getId(), session.getUrl());
    }

    public void refund(Payment payment) throws StripeException {
        if (!props.enabled()) {
            log.info("[dev] Pretending to refund payment {}", payment.getId());
            return;
        }
        Refund.create(RefundCreateParams.builder()
                .setPaymentIntent(payment.getStripePaymentIntentId())
                .build());
    }
}
