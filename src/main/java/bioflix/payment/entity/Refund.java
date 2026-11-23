package bioflix.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A full refund of a payment in Stripe. At most one per payment (unique index). */
@Entity
@Table(name = "refunds")
public class Refund {

    /** Why the money was returned. */
    public enum Reason {
        /** booking.cancelled for a paid booking (customer cancelled, or Booking saw a late payment). */
        BOOKING_CANCELLED,
        /** Stripe reported a payment after the booking was already cancelled/expired. */
        PAID_AFTER_CANCEL
    }

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "stripe_refund_id", unique = true)
    private String stripeRefundId;

    @Column(nullable = false)
    private int amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 40)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Refund() {
        // for JPA
    }

    public Refund(Payment payment, String stripeRefundId, Reason reason, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.paymentId = payment.getId();
        this.stripeRefundId = stripeRefundId;
        this.amount = payment.getAmount();
        this.currency = payment.getCurrency();
        this.reason = reason.name();
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getPaymentId() { return paymentId; }
    public String getStripeRefundId() { return stripeRefundId; }
    public int getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
