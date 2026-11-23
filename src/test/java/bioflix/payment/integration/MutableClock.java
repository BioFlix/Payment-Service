package bioflix.payment.integration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock the tests can move forward (to test the hold time without waiting 15 minutes). */
class MutableClock extends Clock {

    private volatile Instant now = Instant.now();

    void advance(Duration duration) {
        now = now.plus(duration);
    }

    void reset() {
        now = Instant.now();
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
