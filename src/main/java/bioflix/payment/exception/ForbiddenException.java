package bioflix.payment.exception;

/** Mapped to HTTP 403 by GlobalExceptionHandler (e.g. admin endpoint without X-User-Role: ADMIN). */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
