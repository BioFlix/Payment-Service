package bioflix.payment.service;

import bioflix.payment.dto.PaymentResponse;
import bioflix.payment.entity.Payment;
import bioflix.payment.entity.PaymentStatus;
import bioflix.payment.entity.Refund;
import bioflix.payment.exception.NotFoundException;
import bioflix.payment.exception.PaymentProviderException;
import bioflix.payment.messaging.EventTypes;
import bioflix.payment.messaging.PaymentEvents.PaymentCompleted;
import bioflix.payment.messaging.PaymentEvents.PaymentFailed;
import bioflix.payment.messaging.PaymentEvents.PaymentRefunded;
import bioflix.payment.outbox.OutboxService;
import bioflix.payment.repository.PaymentRepository;
import bioflix.payment.repository.RefundRepository;
import bioflix.payment.stripe.StripeGateway;
import com.stripe.exception.StripeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The payment flow:
 * <ol>
 *   <li>booking.created → PENDING payment (no Stripe call yet).</li>
 *   <li>The client asks GET /api/payments/booking/{bookingId} → a Stripe Checkout link is created (valid ≥ 30 min).</li>
 *   <li>Stripe webhook checkout.session.completed → COMPLETED + payment.completed (booking confirms).
 *       Expired/failed checkout → FAILED + payment.failed (booking cancels).</li>
 *   <li>booking.cancelled → paid: refund (REFUNDED + payment.refunded); pending: CANCELLED and the Checkout page
 *       is closed.</li>
 *   <li>Money that arrives AFTER the booking was cancelled is refunded at once (PAID_AFTER_CANCEL).</li>
 * </ol>
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final DateTimeFormatter RECEIPT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final StripeGateway stripe;
    private final OutboxService outbox;
    private final Clock clock;

    public PaymentService(PaymentRepository paymentRepository, RefundRepository refundRepository,
                          StripeGateway stripe, OutboxService outbox, Clock clock) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.stripe = stripe;
        this.outbox = outbox;
        this.clock = clock;
    }

    // ================================================================ booking events

    /** booking.created → PENDING payment. Idempotent on bookingId. */
    @Transactional
    public void handleBookingCreated(UUID bookingId, UUID userId, int amount, String currency, Instant bookingExpiresAt) {
        if (paymentRepository.findByBookingId(bookingId).isPresent()) {
            log.info("Payment for booking {} already exists – booking.created ignored", bookingId);
            return;
        }
        Payment payment = new Payment(bookingId, userId, amount, currency.toUpperCase(Locale.ROOT), bookingExpiresAt);
        paymentRepository.saveAndFlush(payment);
        log.info("Payment {} created for booking {} ({} {})", payment.getId(), bookingId, amount, payment.getCurrency());
    }

    /**
     * booking.cancelled.
     * <ul>
     *   <li>COMPLETED (or Booking says wasPaid) → refund.</li>
     *   <li>PENDING → CANCELLED and the Stripe Checkout page is closed. If Stripe says it was already paid,
     *       the webhook that follows is refunded as PAID_AFTER_CANCEL.</li>
     *   <li>FAILED / CANCELLED / REFUNDED → nothing to do.</li>
     * </ul>
     */
    @Transactional
    public void handleBookingCancelled(UUID bookingId, boolean wasPaid, String reason) {
        Payment payment = paymentRepository.findByBookingId(bookingId).orElse(null);
        if (payment == null) {
            log.warn("booking.cancelled for booking {} without a payment – ignored", bookingId);
            return;
        }
        switch (payment.getStatus()) {
            case COMPLETED -> {
                log.info("Booking {} cancelled ({}) after payment – refunding", bookingId, reason);
                refund(payment, Refund.Reason.BOOKING_CANCELLED);
            }
            case PENDING -> {
                if (wasPaid) {
                    log.warn("booking.cancelled for booking {} says wasPaid, but the payment is still PENDING", bookingId);
                }
                payment.markCancelled();
                closeCheckout(payment);
                log.info("Booking {} cancelled ({}) before payment – payment {} cancelled", bookingId, reason, payment.getId());
            }
            default -> log.info("Booking {} cancelled, payment {} is already {} – nothing to do",
                    bookingId, payment.getId(), payment.getStatus());
        }
        paymentRepository.flush();
    }

    // ================================================================ checkout (client)

    /**
     * The client calls this after POST /api/bookings and redirects to checkoutUrl.
     * Creates the Stripe Checkout session the first time (or again if the old link expired while still pending).
     */
    @Transactional
    public PaymentResponse getOrCreateCheckout(UUID bookingId, UUID userId, boolean admin) {
        Payment payment = findVisible(bookingId, userId, admin);
        Instant now = clock.instant();

        boolean bookingStillHeld = payment.getBookingExpiresAt() == null || payment.getBookingExpiresAt().isAfter(now);
        if (payment.getStatus() == PaymentStatus.PENDING && !payment.hasOpenCheckout(now) && bookingStillHeld) {
            try {
                StripeGateway.CheckoutSession session = stripe.createCheckoutSession(payment, description(payment), now);
                payment.attachCheckout(session.id(), session.url(), session.expiresAt());
                paymentRepository.flush();
                log.info("Checkout session {} created for booking {} (valid until {})",
                        session.id(), bookingId, session.expiresAt());
            } catch (StripeException ex) {
                log.error("Stripe checkout could not be created for booking {}", bookingId, ex);
                throw new PaymentProviderException("Could not start the payment – please try again");
            }
        }
        return PaymentResponse.from(payment, now);
    }

    // ================================================================ Stripe results

    /**
     * Money has been taken in Stripe (checkout.session.completed with payment_status=paid,
     * or checkout.session.async_payment_succeeded). Also used by the dev endpoint.
     */
    @Transactional
    public void handlePaid(UUID paymentId, String paymentIntentId) {
        Payment payment = findById(paymentId);
        Instant now = clock.instant();
        switch (payment.getStatus()) {
            case PENDING -> {
                payment.markPaid(paymentIntentId, receiptNumber(payment, now), stripe.receiptUrl(paymentIntentId), now);
                outbox.enqueue(EventTypes.PAYMENT_COMPLETED, payment.getBookingId(), PaymentCompleted.from(payment));
                log.info("Payment {} completed for booking {}", payment.getId(), payment.getBookingId());
            }
            case CANCELLED, FAILED -> {
                // The booking is already cancelled/expired and its seats released – give the money back at once.
                log.warn("Payment {} for booking {} was paid while {} – refunding", payment.getId(),
                        payment.getBookingId(), payment.getStatus());
                payment.markPaid(paymentIntentId, receiptNumber(payment, now), stripe.receiptUrl(paymentIntentId), now);
                refund(payment, Refund.Reason.PAID_AFTER_CANCEL);
            }
            case COMPLETED, REFUNDED -> {
                if (paymentIntentId != null && payment.getStripePaymentIntent() != null
                        && !paymentIntentId.equals(payment.getStripePaymentIntent())) {
                    log.error("Booking {} was paid TWICE (payment intents {} and {}) – refund {} manually in Stripe",
                            payment.getBookingId(), payment.getStripePaymentIntent(), paymentIntentId, paymentIntentId);
                } else {
                    log.info("Payment {} is already {} – duplicate ignored", payment.getId(), payment.getStatus());
                }
            }
        }
        paymentRepository.flush();
    }

    /** The checkout expired or the payment failed in Stripe → FAILED + payment.failed (booking cancels). */
    @Transactional
    public void handleFailed(UUID paymentId, String reason) {
        Payment payment = findById(paymentId);
        if (payment.getStatus() != PaymentStatus.PENDING) {
            log.info("Payment {} is {} – failure '{}' ignored", payment.getId(), payment.getStatus(), reason);
            return;
        }
        payment.markFailed(reason);
        outbox.enqueue(EventTypes.PAYMENT_FAILED, payment.getBookingId(), PaymentFailed.from(payment));
        paymentRepository.flush();
        log.info("Payment {} for booking {} failed: {}", payment.getId(), payment.getBookingId(), reason);
    }

    // ================================================================ dev mode

    @Transactional
    public PaymentResponse simulate(UUID bookingId, boolean success) {
        Payment payment = paymentRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new NotFoundException("No payment for booking " + bookingId));
        if (success) {
            handlePaid(payment.getId(), "dev_pi_" + bookingId);
        } else {
            handleFailed(payment.getId(), "Simulated failure");
        }
        return PaymentResponse.from(payment, clock.instant());
    }

    // ================================================================ queries

    /** The payment a Stripe Checkout session belongs to: by session id, else by bookingId in the metadata. */
    @Transactional
    public Optional<Payment> findForStripeSession(String sessionId, String bookingIdFromMetadata) {
        Optional<Payment> bySession = sessionId == null ? Optional.empty() : paymentRepository.findByStripeSessionId(sessionId);
        if (bySession.isPresent() || bookingIdFromMetadata == null) {
            return bySession;
        }
        try {
            return paymentRepository.findByBookingId(UUID.fromString(bookingIdFromMetadata));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> getByUser(UUID userId) {
        Instant now = clock.instant();
        return paymentRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(p -> PaymentResponse.from(p, now)).toList();
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> list(PaymentStatus status, int limit) {
        Limit max = Limit.of(Math.clamp(limit, 1, 500));
        List<Payment> payments = status == null
                ? paymentRepository.findAllByOrderByCreatedAtDesc(max)
                : paymentRepository.findByStatusOrderByCreatedAtDesc(status, max);
        Instant now = clock.instant();
        return payments.stream().map(p -> PaymentResponse.from(p, now)).toList();
    }

    // ================================================================ helpers

    private void refund(Payment payment, Refund.Reason reason) {
        if (refundRepository.findByPaymentId(payment.getId()).isPresent()) {
            log.info("Payment {} is already refunded", payment.getId());
            payment.markRefunded();
            return;
        }
        String stripeRefundId;
        try {
            stripeRefundId = stripe.refund(payment);
        } catch (StripeException ex) {
            // Rolls back; the message/webhook is retried. The idempotency key stops a double refund in Stripe.
            throw new PaymentProviderException("Refund failed for payment " + payment.getId() + ": " + ex.getMessage());
        }
        Refund refund = refundRepository.save(new Refund(payment, stripeRefundId, reason, clock.instant()));
        payment.markRefunded();
        outbox.enqueue(EventTypes.PAYMENT_REFUNDED, payment.getBookingId(), PaymentRefunded.from(payment, refund.getId()));
        log.info("Payment {} refunded ({}, Stripe refund {})", payment.getId(), reason, stripeRefundId);
    }

    /** Close the Checkout page of a cancelled payment. Failing to do so is logged, not fatal. */
    private void closeCheckout(Payment payment) {
        try {
            if (!stripe.expireCheckoutSession(payment.getStripeSessionId())) {
                log.warn("Checkout {} for booking {} could not be closed (already paid or expired)",
                        payment.getStripeSessionId(), payment.getBookingId());
            }
        } catch (StripeException ex) {
            log.warn("Could not close checkout {}: {}", payment.getStripeSessionId(), ex.getMessage());
        }
    }

    private Payment findById(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment " + paymentId + " not found"));
    }

    /** Another user's payment looks exactly like a missing one. */
    private Payment findVisible(UUID bookingId, UUID userId, boolean admin) {
        Payment payment = paymentRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new NotFoundException("No payment for booking " + bookingId));
        if (!admin && !payment.getUserId().equals(userId)) {
            throw new NotFoundException("No payment for booking " + bookingId);
        }
        return payment;
    }

    private static String description(Payment payment) {
        return "BioFlix booking " + payment.getBookingId().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    /** e.g. BF-20261123-1A2B3C4D – readable and unique per payment. */
    private static String receiptNumber(Payment payment, Instant when) {
        return "BF-" + RECEIPT_DATE.format(when) + "-"
                + payment.getId().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}
