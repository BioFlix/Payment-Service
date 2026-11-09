package bioflix.payment.controller;

import bioflix.payment.dto.PaymentResponse;
import bioflix.payment.exception.NotFoundException;
import bioflix.payment.service.PaymentService;
import bioflix.payment.service.StripeGateway;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final StripeGateway stripeGateway;

    public PaymentController(PaymentService paymentService, StripeGateway stripeGateway) {
        this.paymentService = paymentService;
        this.stripeGateway = stripeGateway;
    }

    /** Client polls this after POST /api/bookings to get the Stripe checkoutUrl. */
    @GetMapping("/booking/{bookingId}")
    public PaymentResponse getByBooking(@PathVariable Long bookingId) {
        // TODO: check that the booking belongs to X-User-Id
        return paymentService.getByBookingId(bookingId);
    }

    @GetMapping("/me")
    public List<PaymentResponse> myPayments(@RequestHeader("X-User-Id") Long userId) {
        return paymentService.getByUser(userId);
    }

    // ---- Dev mode only: simulate Stripe when stripe.enabled=false ----

    @PostMapping("/dev/{bookingId}/succeed")
    public PaymentResponse simulateSuccess(@PathVariable Long bookingId) {
        requireDevMode();
        return paymentService.simulate(bookingId, true);
    }

    @PostMapping("/dev/{bookingId}/fail")
    public PaymentResponse simulateFailure(@PathVariable Long bookingId) {
        requireDevMode();
        return paymentService.simulate(bookingId, false);
    }

    private void requireDevMode() {
        if (stripeGateway.isEnabled()) {
            throw new NotFoundException("Dev endpoints are disabled when Stripe is enabled");
        }
    }
}
