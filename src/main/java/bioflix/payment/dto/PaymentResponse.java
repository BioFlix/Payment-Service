package bioflix.payment.dto;

import bioflix.payment.entity.Payment;
import bioflix.payment.entity.PaymentStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * The client calls GET /api/payments/booking/{bookingId} right after creating a booking and redirects the browser
 * to checkoutUrl (Stripe hosted checkout). checkoutUrl is only set while the payment is PENDING and the link is
 * still valid. Amounts are in øre.
 */
public record PaymentResponse(
        UUID id,
        UUID bookingId,
        UUID userId,
        int amount,
        String currency,
        PaymentStatus status,
        String checkoutUrl,
        Instant checkoutExpiresAt,
        String receiptNumber,
        String receiptUrl,
        String failureReason,
        Instant createdAt,
        Instant completedAt
) {
    public static PaymentResponse from(Payment p, Instant now) {
        boolean open = p.getStatus() == PaymentStatus.PENDING && p.hasOpenCheckout(now);
        return new PaymentResponse(p.getId(), p.getBookingId(), p.getUserId(), p.getAmount(), p.getCurrency(),
                p.getStatus(), open ? p.getCheckoutUrl() : null, open ? p.getCheckoutExpiresAt() : null,
                p.getReceiptNumber(), p.getReceiptUrl(), p.getFailureReason(), p.getCreatedAt(), p.getCompletedAt());
    }
}
