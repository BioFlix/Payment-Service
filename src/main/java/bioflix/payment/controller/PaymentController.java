package bioflix.payment.controller;

import bioflix.payment.dto.PaymentResponse;
import bioflix.payment.entity.PaymentStatus;
import bioflix.payment.exception.ForbiddenException;
import bioflix.payment.exception.NotFoundException;
import bioflix.payment.service.PaymentService;
import bioflix.payment.stripe.StripeGateway;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * X-User-Id (UUID) and X-User-Role are set by the API gateway. When testing locally without the gateway,
 * send the headers yourself.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    static final String USER_ID = "X-User-Id";
    static final String USER_ROLE = "X-User-Role";

    private final PaymentService paymentService;
    private final StripeGateway stripeGateway;

    public PaymentController(PaymentService paymentService, StripeGateway stripeGateway) {
        this.paymentService = paymentService;
        this.stripeGateway = stripeGateway;
    }

    /**
     * The client calls this after POST /api/bookings and redirects to checkoutUrl.
     * Creates the Stripe Checkout link the first time. 404 if the payment isn't created yet (booking.created is
     * on its way) – retry after a moment – or if it belongs to another user.
     */
    @GetMapping("/booking/{bookingId}")
    public PaymentResponse getByBooking(@RequestHeader(USER_ID) UUID userId,
                                        @RequestHeader(value = USER_ROLE, required = false) String role,
                                        @PathVariable UUID bookingId) {
        return paymentService.getOrCreateCheckout(bookingId, userId, isAdmin(role));
    }

    @GetMapping("/me")
    public List<PaymentResponse> myPayments(@RequestHeader(USER_ID) UUID userId) {
        return paymentService.getByUser(userId);
    }

    /** Admin: all payments, newest first, optionally filtered by status. */
    @GetMapping
    public List<PaymentResponse> list(@RequestHeader(value = USER_ROLE, required = false) String role,
                                      @RequestParam(required = false) PaymentStatus status,
                                      @RequestParam(defaultValue = "100") int limit) {
        if (!isAdmin(role)) {
            throw new ForbiddenException("Only admins can list all payments");
        }
        return paymentService.list(status, limit);
    }

    // ---- Dev mode only (STRIPE_ENABLED=false): simulate the Stripe result ----

    @PostMapping("/dev/{bookingId}/succeed")
    public PaymentResponse simulateSuccess(@PathVariable UUID bookingId) {
        requireDevMode();
        return paymentService.simulate(bookingId, true);
    }

    @PostMapping("/dev/{bookingId}/fail")
    public PaymentResponse simulateFailure(@PathVariable UUID bookingId) {
        requireDevMode();
        return paymentService.simulate(bookingId, false);
    }

    private void requireDevMode() {
        if (stripeGateway.isEnabled()) {
            throw new NotFoundException("Dev endpoints are disabled when Stripe is enabled");
        }
    }

    private static boolean isAdmin(String role) {
        return "ADMIN".equalsIgnoreCase(role);
    }
}
