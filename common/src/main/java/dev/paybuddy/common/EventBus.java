package dev.paybuddy.common;

import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.*;
import org.springframework.amqp.rabbit.connection.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Configuration
public class EventBus {

  @Bean
  Declarables topology() {
    var declarations = new ArrayList<Declarable>();
    declarations.add(new TopicExchange("paybuddy.events", true, false));
    declarations.add(new DirectExchange("paybuddy.dead", true, false));
    for (String queue : List.of(
      "payment.outcomes",
      "order.payments",
      "ledger.payments",
      "reporting.payments"
    )) {
      declarations.add(
        QueueBuilder.durable(queue)
          .deadLetterExchange("paybuddy.dead")
          .deadLetterRoutingKey(queue)
          .build()
      );
      declarations.add(
        new org.springframework.amqp.core.Queue(queue + ".dead", true)
      );
      declarations.add(
        new Binding(
          queue + ".dead",
          Binding.DestinationType.QUEUE,
          "paybuddy.dead",
          queue,
          null
        )
      );
      declarations.add(
        new Binding(
          queue,
          Binding.DestinationType.QUEUE,
          "paybuddy.events",
          queue.equals("payment.outcomes")
            ? "ProviderOutcomeObserved"
            : "PaymentSucceeded",
          null
        )
      );
    }
    return new Declarables(declarations);
  }

  @Bean
  SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
    ConnectionFactory connection
  ) {
    var f = new SimpleRabbitListenerContainerFactory();
    f.setConnectionFactory(connection);
    f.setDefaultRequeueRejected(false);
    f.setPrefetchCount(10);
    f.setAdviceChain(
      RetryInterceptorBuilder.stateless()
        .maxRetries(2)
        .backOffOptions(500, 2, 2000)
        .recoverer(new RejectAndDontRequeueRecoverer())
        .build()
    );
    return f;
  }

  @Component
  public static class Dispatcher {

    private final Db db;
    private final RabbitTemplate rabbit;

    public Dispatcher(Db db, RabbitTemplate rabbit) {
      this.db = db;
      this.rabbit = rabbit;
    }

    @Scheduled(fixedDelay = 1000)
    public void publish() {
      try {
        db.tx(() -> {
          var rows = db.rows(
            "SELECT * FROM outbox WHERE NOT delivered AND next_attempt_at<=now() ORDER BY created_at LIMIT 20 FOR UPDATE SKIP LOCKED"
          );
          for (var row : rows) {
            String id = Json.text(row, "id");
            try {
              var correlation = new CorrelationData(id);
              var props = new MessageProperties();
              props.setContentType("application/json");
              props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
              props.setMessageId(id);
              rabbit.send(
                "paybuddy.events",
                Json.text(row, "eventType"),
                new Message(
                  Json.text(row, "payload").getBytes(
                    java.nio.charset.StandardCharsets.UTF_8
                  ),
                  props
                ),
                correlation
              );
              if (
                !correlation.getFuture().get(5, TimeUnit.SECONDS).isAck() ||
                correlation.getReturned() != null
              ) throw new IllegalStateException(
                "Event was not accepted/routed"
              );
              db.exec("UPDATE outbox SET delivered=true WHERE id=?", id);
            } catch (Exception e) {
              db.exec(
                "UPDATE outbox SET attempts=attempts+1,next_attempt_at=now()+interval '5 seconds' WHERE id=?",
                id
              );
            }
          }
          return null;
        });
      } catch (Exception e) {
        org.slf4j.LoggerFactory.getLogger(getClass()).warn(
          "Outbox will retry: {}",
          e.getMessage()
        );
      }
    }
  }
}
