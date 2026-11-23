package bioflix.payment.exception;

/** Mapped to HTTP 400 by GlobalExceptionHandler (the request itself is wrong, e.g. a seat that doesn't exist). */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
