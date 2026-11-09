package bioflix.payment.dto;

import bioflix.payment.entity.Payment;
import bioflix.payment.entity.PaymentStatus;

import java.time.Instant;

/**
 * The client polls GET /api/payments/booking/{bookingId} right after creating a booking,
 * and redirects the browser to checkoutUrl (Stripe hosted checkout).
 */
public record PaymentResponse(
        Long id,
        Long bookingId,
        long amountMinor,
        String currency,
        PaymentStatus status,
        String checkoutUrl,
        String failureReason,
        Instant createdAt
) {
    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(p.getId(), p.getBookingId(), p.getAmountMinor(), p.getCurrency(),
                p.getStatus(), p.getCheckoutUrl(), p.getFailureReason(), p.getCreatedAt());
    }
}
