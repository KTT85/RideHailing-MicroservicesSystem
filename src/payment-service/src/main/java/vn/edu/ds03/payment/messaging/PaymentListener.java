package vn.edu.ds03.payment.messaging;

import vn.edu.ds03.payment.exception.InvalidCommand;
import vn.edu.ds03.payment.config.MessagingConfiguration;
import vn.edu.ds03.payment.model.Payment;
import vn.edu.ds03.payment.dto.PaymentEvent;
import vn.edu.ds03.payment.service.PaymentService;
import vn.edu.ds03.payment.config.PaymentSettings;

import java.nio.charset.StandardCharsets;
import org.slf4j.*;
import org.springframework.amqp.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class PaymentListener {
    private static final Logger log = LoggerFactory.getLogger(PaymentListener.class);
    private final PaymentService service;
    private final ObjectMapper json;
    private final ConfirmedPublisher publisher;
    private final PaymentSettings settings;
    public PaymentListener(PaymentService service, ObjectMapper json, ConfirmedPublisher publisher,
                           PaymentSettings settings) {
        this.service = service; this.json = json; this.publisher = publisher; this.settings = settings;
    }

    @RabbitListener(queues = MessagingConfiguration.COMMANDS)
    public void receive(Message message) {
        if (message.getBody().length > settings.maxMessageBytes())
            throw new AmqpRejectAndDontRequeueException("Payment message exceeds size limit");
        PaymentEvent event;
        try {
            event = json.readValue(new String(message.getBody(), StandardCharsets.UTF_8), PaymentEvent.class);
            if (event == null) throw new InvalidCommand("Empty event");
            event.validate();
        } catch (RuntimeException ex) {
            throw new AmqpRejectAndDontRequeueException("Invalid payment event", ex);
        }
        try {
            service.accept(event);
        } catch (InvalidCommand ex) {
            throw new AmqpRejectAndDontRequeueException("Conflicting payment command", ex);
        } catch (RuntimeException ex) {
            Object header = message.getMessageProperties().getHeaders().get("payment-retry-count");
            if (header != null && (!(header instanceof Number n) || n.longValue() < 0 || n.longValue() > 20))
                throw new AmqpRejectAndDontRequeueException("Invalid retry counter");
            int count = header instanceof Number n ? n.intValue() : 0;
            if (count >= settings.maxAttempts())
                throw new AmqpRejectAndDontRequeueException("Payment consumer retry exhausted", ex);
            Message retry = MessageBuilder.fromClonedMessage(message)
                    .setHeader("payment-retry-count", count + 1)
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT).build();
            try {
                publisher.send(MessagingConfiguration.DIRECT, MessagingConfiguration.RETRY, retry);
            } catch (RuntimeException publishFailure) {
                throw new ImmediateRequeueAmqpException("Retry publish unconfirmed", publishFailure);
            }
            log.warn("service=payment-service event=ConsumerRetry correlationId={} attempt={}",
                    event.correlationId(), count + 1);
        }
    }
}
