package bioflix.payment.entity;

/**
 * PENDING   – Stripe checkout created, waiting for the customer
 * COMPLETED – paid (checkout.session.completed webhook)
 * FAILED    – checkout expired / payment failed
 * CANCELLED – booking was cancelled before payment
 * REFUNDED  – booking cancelled after payment, money returned
 */
public enum PaymentStatus {
    PENDING,
    COMPLETED,
    FAILED,
    CANCELLED,
    REFUNDED
}
