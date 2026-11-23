package bioflix.payment.messaging;

import bioflix.payment.entity.Payment;

import java.util.UUID;

/**
 * The data part of the events we publish, exactly as in the event contract (cinema-data-model.html).
 * Amounts are in øre.
 */
public final class PaymentEvents {

    private PaymentEvents() {
    }

    /** payment.completed (→ booking-service, notification-service) */
    public record PaymentCompleted(
            UUID paymentId,
            UUID bookingId,
            UUID userId,
            int amount,
            String currency,
            String receiptNumber,
            String receiptUrl
    ) {
        public static PaymentCompleted from(Payment p) {
            return new PaymentCompleted(p.getId(), p.getBookingId(), p.getUserId(), p.getAmount(), p.getCurrency(),
                    p.getReceiptNumber(), p.getReceiptUrl());
        }
    }

    /** payment.failed (→ booking-service, notification-service) */
    public record PaymentFailed(
            UUID paymentId,
            UUID bookingId,
            UUID userId,
            String reason
    ) {
        public static PaymentFailed from(Payment p) {
            return new PaymentFailed(p.getId(), p.getBookingId(), p.getUserId(), p.getFailureReason());
        }
    }

    /** payment.refunded (→ notification-service) */
    public record PaymentRefunded(
            UUID paymentId,
            UUID refundId,
            UUID bookingId,
            UUID userId,
            int amount,
            String currency
    ) {
        public static PaymentRefunded from(Payment p, UUID refundId) {
            return new PaymentRefunded(p.getId(), refundId, p.getBookingId(), p.getUserId(), p.getAmount(),
                    p.getCurrency());
        }
    }
}
