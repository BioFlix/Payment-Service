package bioflix.payment.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/** The data part of the booking events we consume (producer: booking-service). Ids are UUID strings. */
public final class BookingEvents {

    private BookingEvents() {
    }

    /** booking.created: bookingId, userId, showtimeId, amount (øre), currency, seats[], expiresAt */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BookingCreated(
            String bookingId,
            String userId,
            String showtimeId,
            Integer amount,
            String currency,
            Instant expiresAt
    ) {
    }

    /** booking.cancelled: bookingId, userId, reason (USER | PAYMENT_FAILED | EXPIRED | SHOWTIME_CANCELLED), wasPaid */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BookingCancelled(
            String bookingId,
            String userId,
            String reason,
            Boolean wasPaid
    ) {
    }
}
