package vn.edu.ds03.trip.payment;

import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
class PaymentMessaging {
    static final String EXCHANGE = "ride.events";
    static final String QUEUE = "trip.payment.results";

    @Bean
    Declarables tripPaymentBindings() {
        var exchange = new TopicExchange(EXCHANGE);
        var dlq = new Queue(QUEUE + ".dlq");
        var queue = QueueBuilder.durable(QUEUE).deadLetterExchange("")
                .deadLetterRoutingKey(dlq.getName()).build();
        var declarations = new ArrayList<Declarable>(List.of(exchange, queue, dlq));
        for (String key : PaymentContract.RESULTS)
            declarations.add(BindingBuilder.bind(queue).to(exchange).with(key));
        return new Declarables(declarations);
    }
}
