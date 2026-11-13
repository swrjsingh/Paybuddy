package dev.paybuddy.paymentintent;

import dev.paybuddy.common.*;
import java.util.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class PaymentController {

  private final Db db;
  private final InternalHttp http;
  private final String orders, routing, providers;

  public PaymentController(
    Db db,
    InternalHttp http,
    @Value("${paybuddy.order-url}") String orders,
    @Value("${paybuddy.routing-url}") String routing,
    @Value("${paybuddy.provider-url}") String providers
  ) {
    this.db = db;
    this.http = http;
    this.orders = orders;
    this.routing = routing;
    this.providers = providers;
  }

  private void history(String id, String kind, String message) {
    db.exec(
      "INSERT INTO history VALUES(?,?,?,?,now())",
      Json.id(),
      id,
      kind,
      message
    );
  }

  @PostMapping("/payment-intents")
  public Map<String, Object> create(
    @RequestHeader(value = "Idempotency-Key", required = false) String key,
    @RequestBody Map<String, Object> body,
    Authentication auth
  ) {
    var actor = Actor.of(auth);
    if (
      key == null || key.length() < 8 || key.length() > 120
    ) throw ApiError.bad(
      "Supply an Idempotency-Key containing 8–120 characters"
    );
    String orderId = Json.text(body, "orderId"),
      method = Json.text(body, "method");
    if (!Set.of("UPI", "GENERIC").contains(method)) throw ApiError.bad(
      "Method must be UPI or GENERIC"
    );
    String hash = Json.hash(orderId + "|" + method);
    var order = http.get(orders + "/internal/orders/" + orderId);
    if (
      !actor.merchantId().equals(Json.text(order, "merchantId"))
    ) throw ApiError.notFound();
    return db.tx(() -> {
      String candidate = Json.id();
      db.exec(
        "INSERT INTO idempotency_keys VALUES(?,?,?,?) ON CONFLICT DO NOTHING",
        actor.merchantId(),
        key,
        hash,
        candidate
      );
      var claim = db.one(
        "SELECT * FROM idempotency_keys WHERE merchant_id=? AND key=? FOR UPDATE",
        actor.merchantId(),
        key
      );
      if (
        !hash.equals(Json.text(claim, "requestHash"))
      ) throw ApiError.conflict(
        "This idempotency key was already used for a different request"
      );
      if (
        !db
          .rows("SELECT id FROM intents WHERE id=?", claim.get("intentId"))
          .isEmpty()
      ) return db.one(
        "SELECT * FROM intents WHERE id=?",
        claim.get("intentId")
      );
      if ("PAID".equals(Json.text(order, "status"))) throw ApiError.conflict(
        "Order is already paid"
      );
      int inserted = db.exec(
        "INSERT INTO intents(id,order_id,merchant_id,amount_minor,currency,method) VALUES(?,?,?,?,?,?) ON CONFLICT(order_id) DO NOTHING",
        candidate,
        orderId,
        actor.merchantId(),
        Json.number(order, "amountMinor"),
        order.get("currency"),
        method
      );
      var intent = db.one("SELECT * FROM intents WHERE order_id=?", orderId);
      if (!method.equals(Json.text(intent, "method"))) throw ApiError.conflict(
        "This order already has a payment intent with a different method"
      );
      db.exec(
        "UPDATE idempotency_keys SET intent_id=? WHERE merchant_id=? AND key=?",
        intent.get("id"),
        actor.merchantId(),
        key
      );
      if (inserted == 1) history(
        candidate,
        "CREATED",
        "Payment intent durably created. Transport retries reuse this intent."
      );
      return intent;
    });
  }

  @GetMapping("/payment-intents")
  public List<Map<String, Object>> list(Authentication auth) {
    return db.rows(
      "SELECT * FROM intents WHERE merchant_id=? ORDER BY created_at DESC LIMIT 200",
      Actor.of(auth).merchantId()
    );
  }

  @GetMapping("/payment-intents/{id}")
  public Map<String, Object> get(@PathVariable String id, Authentication auth) {
    return db.one(
      "SELECT * FROM intents WHERE id=? AND merchant_id=?",
      id,
      Actor.of(auth).merchantId()
    );
  }

  @GetMapping("/payment-intents/{id}/timeline")
  public List<Map<String, Object>> timeline(
    @PathVariable String id,
    Authentication auth
  ) {
    get(id, auth);
    return db.rows(
      "SELECT * FROM history WHERE intent_id=? ORDER BY created_at,id",
      id
    );
  }

  @Scheduled(fixedDelay = 1000)
  public void work() {
    List<Map<String, Object>> work;
    try {
      work = db.tx(() -> {
        var rows = db.rows(
          "SELECT * FROM intents WHERE status IN ('CREATED','PROCESSING') AND next_attempt_at<=now() ORDER BY created_at LIMIT 10 FOR UPDATE SKIP LOCKED"
        );
        for (var row : rows)
          db.exec(
            "UPDATE intents SET next_attempt_at=now()+interval '10 seconds',worker_attempts=worker_attempts+1 WHERE id=?",
            row.get("id")
          );
        return rows;
      });
    } catch (Exception e) {
      return;
    }
    for (var row : work) {
      String id = Json.text(row, "id");
      try {
        process(id);
      } catch (Exception e) {
        db.tx(() -> {
          var current = db.one(
            "SELECT * FROM intents WHERE id=? FOR UPDATE",
            id
          );
          if (
            Set.of("SUCCEEDED", "FAILED").contains(Json.text(current, "status"))
          ) return null;
          String error =
            "Provider result unavailable; recovering the same attempt. " +
            e.getClass().getSimpleName();
          db.exec(
            "UPDATE intents SET last_error=?,review_status=CASE WHEN worker_attempts>=12 THEN 'RECONCILIATION_REQUIRED' ELSE review_status END WHERE id=?",
            error,
            id
          );
          if (current.get("lastError") == null) history(id, "RECOVERY", error);
          return null;
        });
      }
    }
  }

  private void process(String id) {
    var current = db.one("SELECT * FROM intents WHERE id=?", id);
    if (
      !Set.of("CREATED", "PROCESSING").contains(Json.text(current, "status"))
    ) return;
    if (current.get("providerId") == null) {
      var route = http.post(
        routing + "/internal/routes/select",
        Map.of(
          "merchantId",
          current.get("merchantId"),
          "method",
          current.get("method")
        )
      );
      db.tx(() -> {
        var locked = db.one("SELECT * FROM intents WHERE id=? FOR UPDATE", id);
        if (
          locked.get("providerId") == null &&
          "CREATED".equals(Json.text(locked, "status"))
        ) {
          db.exec(
            "UPDATE intents SET provider_id=?,routing_reason=?,status='PROCESSING',version=version+1,updated_at=now() WHERE id=?",
            route.get("id"),
            route.get("reason"),
            id
          );
          history(id, "ROUTED", Json.text(route, "reason"));
        }
        return null;
      });
      current = db.one("SELECT * FROM intents WHERE id=?", id);
    }
    String provider = Json.text(current, "providerId");
    if (provider.isEmpty()) return;
    Map<String, Object> result;
    if (current.get("providerTransactionId") == null) {
      try {
        result = http.post(
          providers + "/internal/providers/" + provider + "/payments",
          Map.of(
            "requestKey",
            id,
            "intentId",
            id,
            "orderId",
            current.get("orderId"),
            "merchantId",
            current.get("merchantId"),
            "amountMinor",
            current.get("amountMinor"),
            "currency",
            current.get("currency"),
            "method",
            current.get("method")
          )
        );
      } catch (org.springframework.web.client.RestClientException uncertain) {
        result = http.get(
          providers +
            "/internal/providers/" +
            provider +
            "/payments/by-request/" +
            id
        );
      }
    } else {
      result = http.get(
        providers +
          "/internal/providers/" +
          provider +
          "/payments/by-request/" +
          id
      );
    }
    final var accepted = result;
    db.tx(() -> {
      var locked = db.one("SELECT * FROM intents WHERE id=? FOR UPDATE", id);
      if (locked.get("providerTransactionId") == null) {
        db.exec(
          "UPDATE intents SET provider_transaction_id=?,checkout_token=?,last_error=NULL,updated_at=now() WHERE id=?",
          accepted.get("id"),
          accepted.get("checkoutToken"),
          id
        );
        history(
          id,
          "PROVIDER_ACCEPTED",
          "Provider accepted the stable attempt. Awaiting simulated approval."
        );
      } else if (locked.get("checkoutToken") == null) {
        db.exec(
          "UPDATE intents SET checkout_token=? WHERE id=?",
          accepted.get("checkoutToken"),
          id
        );
      }
      return null;
    });
    if (Set.of("SUCCEEDED", "FAILED").contains(Json.text(result, "status"))) {
      var event = Json.read(Json.text(result, "outcomePayload"));
      event.put("eventId", Json.text(event, "eventId") + "-poll");
      event.put("source", "STATUS_POLL");
      observed(Json.write(event));
    }
  }

  @RabbitListener(queues = "payment.outcomes")
  public void observed(String raw) {
    var e = Json.read(raw);
    db.tx(() -> {
      String id = Json.text(e, "intentId");
      var intent = db.one("SELECT * FROM intents WHERE id=? FOR UPDATE", id);
      if (
        !Json.text(intent, "providerId").equals(Json.text(e, "providerId")) ||
        !Json.text(intent, "merchantId").equals(Json.text(e, "merchantId")) ||
        !Json.text(intent, "orderId").equals(Json.text(e, "orderId")) ||
        Json.number(intent, "amountMinor") != Json.number(e, "amountMinor") ||
        !Json.text(intent, "currency").equals(Json.text(e, "currency")) ||
        !id.equals(Json.text(e, "attemptId"))
      ) throw ApiError.conflict("Outcome does not match the durable attempt");
      if (
        intent.get("providerTransactionId") != null &&
        !Json.text(intent, "providerTransactionId").equals(
          Json.text(e, "providerTransactionId")
        )
      ) throw ApiError.conflict("Provider transaction reference mismatch");
      if (!db.first(Json.text(e, "eventId"))) return null;
      String next = Json.text(e, "status"),
        previous = Json.text(intent, "status");
      if (
        !Set.of("PROCESSING", "SUCCEEDED", "FAILED").contains(next)
      ) throw ApiError.bad("Unsupported provider state");
      if (Set.of("SUCCEEDED", "FAILED").contains(previous)) {
        if (!next.equals(previous) && !"PROCESSING".equals(next)) {
          db.exec(
            "UPDATE intents SET review_status='RECONCILIATION_REQUIRED' WHERE id=?",
            id
          );
          history(
            id,
            "CONFLICT",
            "Contradictory terminal provider observation preserved for investigation."
          );
        } else history(
          id,
          "OBSERVATION",
          "Repeated/late " + next + " observed; financial state unchanged."
        );
        return null;
      }
      if ("PROCESSING".equals(next)) {
        history(id, "OBSERVATION", "Provider remains processing.");
        return null;
      }
      if (!"PROCESSING".equals(previous)) throw ApiError.conflict(
        "Intent was not dispatched"
      );
      db.exec(
        "UPDATE intents SET status=?,provider_transaction_id=coalesce(provider_transaction_id,?),review_status='NONE',last_error=NULL,version=version+1,updated_at=now() WHERE id=? AND version=?",
        next,
        e.get("providerTransactionId"),
        id,
        Json.number(intent, "version")
      );
      history(
        id,
        next,
        "Verified provider outcome via " +
          (e.containsKey("source") ? e.get("source") : "signed callback") +
          "; finalized once."
      );
      if ("SUCCEEDED".equals(next)) {
        var outcome = new LinkedHashMap<String, Object>(e);
        outcome.put("eventId", id + "-succeeded");
        outcome.put("eventType", "PaymentSucceeded");
        outcome.put("schemaVersion", 1);
        db.event(id + "-succeeded", "PaymentSucceeded", outcome);
      }
      return null;
    });
  }
}
