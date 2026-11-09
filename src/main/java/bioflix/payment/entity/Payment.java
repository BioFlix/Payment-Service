package bioflix.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** One payment per booking – also makes booking.created handling idempotent. */
    @Column(nullable = false, unique = true)
    private Long bookingId;

    @Column(nullable = false)
    private Long userId;

    /** Amount in minor units (øre) – exactly what Stripe expects. */
    @Column(nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(unique = true)
    private String stripeSessionId;

    private String stripePaymentIntentId;

    @Column(length = 1000)
    private String checkoutUrl;

    private String failureReason;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    protected Payment() {
        // for JPA
    }

    public Payment(Long bookingId, Long userId, long amountMinor, String currency) {
        this.bookingId = bookingId;
        this.userId = userId;
        this.amountMinor = amountMinor;
        this.currency = currency;
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

    public void attachCheckout(String sessionId, String url) {
        this.stripeSessionId = sessionId;
        this.checkoutUrl = url;
    }

    public void markCompleted(String paymentIntentId) {
        this.status = PaymentStatus.COMPLETED;
        this.stripePaymentIntentId = paymentIntentId;
    }

    public void markFailed(String reason) {
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
    }

    public void markCancelled() {
        this.status = PaymentStatus.CANCELLED;
    }

    public void markRefunded() {
        this.status = PaymentStatus.REFUNDED;
    }

    public Long getId() { return id; }
    public Long getBookingId() { return bookingId; }
    public Long getUserId() { return userId; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public PaymentStatus getStatus() { return status; }
    public String getStripeSessionId() { return stripeSessionId; }
    public String getStripePaymentIntentId() { return stripePaymentIntentId; }
    public String getCheckoutUrl() { return checkoutUrl; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
