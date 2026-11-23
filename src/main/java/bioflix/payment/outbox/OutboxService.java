package bioflix.payment.outbox;

import bioflix.payment.messaging.EventEnvelope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * Writes an event to outbox_events in the SAME transaction as the payment change. Either both are saved or
 * neither is, so we never mark a payment completed without telling booking (or tell it about a change that was
 * rolled back). OutboxRelay publishes the rows afterwards.
 */
@Service
public class OutboxService {

    private final OutboxEventRepository repository;
    private final JsonMapper jsonMapper;

    public OutboxService(OutboxEventRepository repository, JsonMapper jsonMapper) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
    }

    /** Must be called inside the transaction that changes the payment. correlationId = bookingId (whole purchase). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String eventType, UUID bookingId, Object data) {
        EventEnvelope<Object> envelope = EventEnvelope.create(eventType, bookingId.toString(), data);
        String payload = jsonMapper.writeValueAsString(envelope);
        repository.save(new OutboxEvent(envelope.eventId(), bookingId, eventType, payload, envelope.occurredAt()));
    }
}
