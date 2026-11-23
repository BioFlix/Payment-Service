package bioflix.payment.messaging;

/** Event types (= routing keys on cinema.events) that payment-service publishes or consumes. */
public final class EventTypes {

    // consumed by us (producer: booking-service)
    public static final String BOOKING_CREATED = "booking.created";
    public static final String BOOKING_CANCELLED = "booking.cancelled";

    // published by us
    public static final String PAYMENT_COMPLETED = "payment.completed";
    public static final String PAYMENT_FAILED = "payment.failed";
    public static final String PAYMENT_REFUNDED = "payment.refunded";

    private EventTypes() {
    }
}
