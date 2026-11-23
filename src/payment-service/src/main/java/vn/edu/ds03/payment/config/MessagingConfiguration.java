package vn.edu.ds03.payment.config;


import java.util.List;
import java.util.ArrayList;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.*;

@Configuration
public class MessagingConfiguration {
    public static final String EVENTS = "ride.events";
    public static final String COMMANDS = "payment.commands";
    public static final String RETRY = "payment.retry";
    public static final String DEAD = "payment.dlq";
    public static final String DIRECT = "payment.delivery";

    @Bean
    Declarables paymentTopology() {
        TopicExchange events = new TopicExchange(EVENTS, true, false);
        DirectExchange delivery = new DirectExchange(DIRECT, true, false);
        Queue commands = QueueBuilder.durable(COMMANDS)
                .deadLetterExchange(DIRECT).deadLetterRoutingKey(DEAD).build();
        Queue retry = QueueBuilder.durable(RETRY).ttl(5000)
                .deadLetterExchange(DIRECT).deadLetterRoutingKey(COMMANDS).build();
        Queue dead = QueueBuilder.durable(DEAD).build();
        List<Declarable> all = new ArrayList<>(List.of(events, delivery, commands, retry, dead,
                BindingBuilder.bind(commands).to(delivery).with(COMMANDS),
                BindingBuilder.bind(retry).to(delivery).with(RETRY),
                BindingBuilder.bind(dead).to(delivery).with(DEAD)));
        for (String type : List.of("PaymentRequested", "PaymentRefundRequested",
                "CashPaymentConfirmationRequested", "TripCompleted"))
            all.add(BindingBuilder.bind(commands).to(events).with(type));
        return new Declarables(all);
    }
}
