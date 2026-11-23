package bioflix.payment.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * All services share the cinema.events topic exchange (routing key = event type).
 * payment-service owns one queue for the booking events it cares about. Messages that still fail after the
 * listener retries go to our own dead-letter queue so they can be inspected instead of looping.
 */
@Configuration
public class RabbitConfig {

    public static final String BOOKING_QUEUE = "payment-service.booking-events";
    public static final String DEAD_LETTER_QUEUE = "payment-service.dead-letter";

    @Bean
    TopicExchange eventsExchange(@Value("${app.rabbitmq.exchange}") String exchange) {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    Declarables bookingQueues(TopicExchange eventsExchange,
                              @Value("${app.rabbitmq.dead-letter-exchange}") String deadLetterExchange) {
        DirectExchange dlx = new DirectExchange(deadLetterExchange, true, false);
        Queue dlq = QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
        Queue bookings = QueueBuilder.durable(BOOKING_QUEUE)
                .deadLetterExchange(deadLetterExchange)
                .deadLetterRoutingKey(DEAD_LETTER_QUEUE)
                .build();

        Binding created = BindingBuilder.bind(bookings).to(eventsExchange).with(EventTypes.BOOKING_CREATED);
        Binding cancelled = BindingBuilder.bind(bookings).to(eventsExchange).with(EventTypes.BOOKING_CANCELLED);
        Binding dlqBinding = BindingBuilder.bind(dlq).to(dlx).with(DEAD_LETTER_QUEUE);

        return new Declarables(dlx, dlq, bookings, created, cancelled, dlqBinding);
    }
}
