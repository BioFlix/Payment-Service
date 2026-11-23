package bioflix.payment.exception;

/** Stripe could not be reached or refused the request – mapped to HTTP 502. */
public class PaymentProviderException extends RuntimeException {

    public PaymentProviderException(String message) {
        super(message);
    }
}
