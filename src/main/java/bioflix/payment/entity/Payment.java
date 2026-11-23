package bioflix.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/** The payment for one booking. State changes go through the methods below. */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    private UUID id;

    /** One payment per booking (unique) – also makes booking.created handling idempotent. */
    @Column(name = "booking_id", nullable = false, unique = true)
    private UUID bookingId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Amount in øre – exactly what Stripe expects as unit_amount for DKK. */
    @Column(nullable = false)
    private int amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "stripe_session_id", unique = true)
    private String stripeSessionId;

    @Column(name = "checkout_url", length = 1000)
    private String checkoutUrl;

    @Column(name = "checkout_expires_at")
    private Instant checkoutExpiresAt;

    @Column(name = "stripe_payment_intent")
    private String stripePaymentIntent;

    @Column(name = "receipt_number", length = 40)
    private String receiptNumber;

    @Column(name = "receipt_url", length = 1000)
    private String receiptUrl;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "booking_expires_at")
    private Instant bookingExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    private Long version;

    protected Payment() {
        // for JPA
    }

    public Payment(UUID bookingId, UUID userId, int amount, String currency, Instant bookingExpiresAt) {
        this.id = UUID.randomUUID();
        this.bookingId = bookingId;
        this.userId = userId;
        this.amount = amount;
        this.currency = currency;
        this.bookingExpiresAt = bookingExpiresAt;
        this.status = PaymentStatus.PENDING;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    // ------------------------------------------------------------ state changes

    public void attachCheckout(String sessionId, String url, Instant expiresAt) {
        this.stripeSessionId = sessionId;
        this.checkoutUrl = url;
        this.checkoutExpiresAt = expiresAt;
    }

    /** A usable Checkout link exists (not expired yet). */
    public boolean hasOpenCheckout(Instant now) {
        return checkoutUrl != null && checkoutExpiresAt != null && checkoutExpiresAt.isAfter(now);
    }

    /** Money was taken. Also used when it arrives after the booking was cancelled (then refunded right after). */
    public void markPaid(String paymentIntent, String receiptNumber, String receiptUrl, Instant when) {
        this.stripePaymentIntent = paymentIntent;
        this.receiptNumber = receiptNumber;
        this.receiptUrl = receiptUrl;
        this.completedAt = when;
        this.status = PaymentStatus.COMPLETED;
        this.checkoutUrl = null;
    }

    public void markFailed(String reason) {
        this.status = PaymentStatus.FAILED;
        this.failureReason = truncate(reason);
        this.checkoutUrl = null;
    }

    public void markCancelled() {
        this.status = PaymentStatus.CANCELLED;
        this.checkoutUrl = null;
    }

    public void markRefunded() {
        this.status = PaymentStatus.REFUNDED;
    }

    private static String truncate(String s) {
        return s == null ? null : s.substring(0, Math.min(s.length(), 500));
    }

    // ------------------------------------------------------------ getters

    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public UUID getUserId() { return userId; }
    public int getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public PaymentStatus getStatus() { return status; }
    public String getStripeSessionId() { return stripeSessionId; }
    public String getCheckoutUrl() { return checkoutUrl; }
    public Instant getCheckoutExpiresAt() { return checkoutExpiresAt; }
    public String getStripePaymentIntent() { return stripePaymentIntent; }
    public String getReceiptNumber() { return receiptNumber; }
    public String getReceiptUrl() { return receiptUrl; }
    public String getFailureReason() { return failureReason; }
    public Instant getBookingExpiresAt() { return bookingExpiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Long getVersion() { return version; }
}
