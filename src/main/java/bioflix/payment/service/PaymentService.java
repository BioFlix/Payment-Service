package bioflix.payment.service;

import com.stripe.exception.StripeException;
import bioflix.payment.dto.PaymentResponse;
import bioflix.payment.entity.Payment;
import bioflix.payment.entity.PaymentStatus;
import bioflix.payment.exception.NotFoundException;
import bioflix.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final StripeGateway stripeGateway;

    public PaymentService(PaymentRepository paymentRepository, StripeGateway stripeGateway) {
        this.paymentRepository = paymentRepository;
        this.stripeGateway = stripeGateway;
    }

    // ------------------------------------------------- triggered by booking (via RabbitMQ later)

    /**
     * Creates a payment + Stripe checkout session for a new booking.
     * TODO (RabbitMQ): call this from a listener on booking.created
     */
    @Transactional
    public PaymentResponse createPaymentForBooking(Long bookingId, Long userId, long amountMinor,
                                                   String currency, String description) {
        if (paymentRepository.existsByBookingId(bookingId)) {
            log.info("Payment for booking {} already exists", bookingId);
            return getByBookingId(bookingId);
        }

        Payment payment = paymentRepository.save(new Payment(bookingId, userId, amountMinor, currency));
        try {
            StripeGateway.CheckoutSession session = stripeGateway.createCheckoutSession(payment, description);
            payment.attachCheckout(session.id(), session.url());
        } catch (StripeException ex) {
            log.error("Stripe checkout failed for booking {}", bookingId, ex);
            fail(payment, "Could not start checkout: " + ex.getMessage());
        }
        return PaymentResponse.from(payment);
    }

    /**
     * Booking was cancelled: refund if already paid, otherwise just close the payment.
     * TODO (RabbitMQ): call this from a listener on booking.cancelled
     */
    @Transactional
    public void handleBookingCancelled(Long bookingId) {
        paymentRepository.findByBookingId(bookingId).ifPresent(payment -> {
            switch (payment.getStatus()) {
                case COMPLETED -> refund(payment);
                case PENDING -> payment.markCancelled();
                default -> log.info("Booking {} cancelled, payment already {}", bookingId, payment.getStatus());
            }
        });
    }

    // ------------------------------------------------- reactions to Stripe webhooks

    @Transactional
    public void handleCheckoutCompleted(String sessionId, String paymentIntentId) {
        Payment payment = paymentRepository.findByStripeSessionId(sessionId)
                .orElseThrow(() -> new NotFoundException("No payment for Stripe session " + sessionId));
        complete(payment, paymentIntentId);
    }

    @Transactional
    public void handleCheckoutFailed(String sessionId, String reason) {
        paymentRepository.findByStripeSessionId(sessionId).ifPresent(payment -> fail(payment, reason));
    }

    // ------------------------------------------------- dev mode (stripe.enabled=false)

    @Transactional
    public PaymentResponse simulate(Long bookingId, boolean success) {
        Payment payment = findByBookingOrThrow(bookingId);
        if (success) {
            complete(payment, "dev_pi_" + bookingId);
        } else {
            fail(payment, "Simulated failure");
        }
        return PaymentResponse.from(payment);
    }

    // ------------------------------------------------- queries

    @Transactional(readOnly = true)
    public PaymentResponse getByBookingId(Long bookingId) {
        return PaymentResponse.from(findByBookingOrThrow(bookingId));
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> getByUser(Long userId) {
        return paymentRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(PaymentResponse::from).toList();
    }

    // ------------------------------------------------- state transitions

    private void complete(Payment payment, String paymentIntentId) {
        if (payment.getStatus() != PaymentStatus.PENDING) {
            log.warn("Payment {} is {} – ignoring completion", payment.getId(), payment.getStatus());
            return;
        }
        payment.markCompleted(paymentIntentId);
        // TODO (RabbitMQ): publish payment.completed – booking confirms, notification sends receipt
    }

    private void fail(Payment payment, String reason) {
        if (payment.getStatus() != PaymentStatus.PENDING) {
            return;
        }
        payment.markFailed(reason);
        // TODO (RabbitMQ): publish payment.failed – booking cancels and releases the seats
    }

    private void refund(Payment payment) {
        try {
            stripeGateway.refund(payment);
        } catch (StripeException ex) {
            throw new IllegalStateException("Refund failed for payment " + payment.getId(), ex);
        }
        payment.markRefunded();
        // TODO (RabbitMQ): publish payment.refunded
    }

    private Payment findByBookingOrThrow(Long bookingId) {
        return paymentRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new NotFoundException("No payment for booking " + bookingId));
    }
}
