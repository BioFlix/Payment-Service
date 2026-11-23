package bioflix.payment.exception;

/** Mapped to HTTP 409 by GlobalExceptionHandler (business rule violated, e.g. seat already taken). */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
