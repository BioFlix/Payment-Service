package bioflix.payment.entity;

/**
 * PENDING   – created from booking.created, waiting for the customer to pay in Stripe Checkout
 * COMPLETED – paid (checkout.session.completed)
 * FAILED    – the payment failed in Stripe
 * CANCELLED – the booking was cancelled/expired before it was paid
 * REFUNDED  – money returned (booking cancelled after payment, or paid after the booking was cancelled)
 */
public enum PaymentStatus {
    PENDING,
    COMPLETED,
    FAILED,
    CANCELLED,
    REFUNDED
}
