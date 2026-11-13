package dev.paybuddy.provideradapter;

import dev.paybuddy.common.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

@RestController
public class ProviderController {

  private final Db db;
  private final String secret, callback;
  private final RestClient client;

  public ProviderController(
    Db db,
    @Value("${paybuddy.webhook-secret}") String secret,
    @Value("${paybuddy.callback-url}") String callback
  ) {
    this.db = db;
    this.secret = secret;
    this.callback = callback;
    var f = new SimpleClientHttpRequestFactory();
    f.setConnectTimeout(2000);
    f.setReadTimeout(3000);
    this.client = RestClient.builder().requestFactory(f).build();
  }

  @PostMapping("/internal/providers/{provider}/payments")
  public Map<String, Object> submit(
    @PathVariable String provider,
    @RequestBody Map<String, Object> body
  ) {
    var scenario = db.one(
      "SELECT * FROM scenarios WHERE provider_id=?",
      provider
    );
    long amount = Json.number(body, "amountMinor");
    if (
      amount <= 0 || !"INR".equals(Json.text(body, "currency"))
    ) throw ApiError.bad("Invalid money");
    if (
      !(provider.startsWith("upi-") ? "UPI" : "GENERIC").equals(
        Json.text(body, "method")
      )
    ) throw ApiError.bad("Provider does not support method");
    var result = db.tx(() -> {
      db.exec(
        "INSERT INTO provider_transactions(id,provider_id,request_key,intent_id,order_id,merchant_id,amount_minor,currency,method,checkout_token,mode) VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(provider_id,request_key) DO NOTHING",
        Json.id(),
        provider,
        Json.text(body, "requestKey"),
        body.get("intentId"),
        body.get("orderId"),
        body.get("merchantId"),
        amount,
        "INR",
        body.get("method"),
        Json.id(),
        scenario.get("mode")
      );
      var row = db.one(
        "SELECT * FROM provider_transactions WHERE provider_id=? AND request_key=?",
        provider,
        Json.text(body, "requestKey")
      );
      if (
        Json.number(row, "amountMinor") != amount ||
        !Json.text(row, "intentId").equals(Json.text(body, "intentId")) ||
        !Json.text(row, "merchantId").equals(Json.text(body, "merchantId")) ||
        !Json.text(row, "orderId").equals(Json.text(body, "orderId"))
      ) throw ApiError.conflict(
        "Provider request key reused with different parameters"
      );
      return row;
    });
    if ("TIMEOUT".equals(Json.text(result, "mode"))) {
      try {
        Thread.sleep(4500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    return result;
  }

  @GetMapping("/internal/providers/{provider}/payments/by-request/{key}")
  public Map<String, Object> status(
    @PathVariable String provider,
    @PathVariable String key
  ) {
    return db.one(
      "SELECT * FROM provider_transactions WHERE provider_id=? AND request_key=?",
      provider,
      key
    );
  }

  @GetMapping("/simulator/payments/{token}/view")
  public Map<String, Object> view(@PathVariable String token) {
    var row = token(token);
    return Map.of(
      "providerId",
      row.get("providerId"),
      "amountMinor",
      row.get("amountMinor"),
      "currency",
      row.get("currency"),
      "status",
      row.get("status"),
      "mode",
      row.get("mode"),
      "vpa",
      "demo-merchant@paybuddy",
      "method",
      row.get("method"),
      "simulated",
      true
    );
  }

  private Map<String, Object> token(String token) {
    return db.one(
      "SELECT * FROM provider_transactions WHERE checkout_token=? AND created_at>now()-interval '2 hours'",
      token
    );
  }

  @PostMapping("/simulator/payments/{token}/complete")
  public Map<String, Object> complete(
    @PathVariable String token,
    @RequestBody Map<String, Object> body
  ) {
    String requested = Json.text(body, "status");
    if (!Set.of("SUCCEEDED", "FAILED").contains(requested)) throw ApiError.bad(
      "Choose SUCCEEDED or FAILED"
    );
    return db.tx(() -> {
      token(token);
      var row = db.one(
        "SELECT * FROM provider_transactions WHERE checkout_token=? FOR UPDATE",
        token
      );
      if (!"PROCESSING".equals(Json.text(row, "status"))) return Map.of(
        "status",
        row.get("status"),
        "simulated",
        true
      );
      String status = "FAILURE".equals(Json.text(row, "mode"))
        ? "FAILED"
        : requested;
      var event = new LinkedHashMap<String, Object>();
      event.put("eventId", row.get("id") + "-outcome");
      event.put("eventType", "ProviderOutcomeObserved");
      event.put("schemaVersion", 1);
      event.put("intentId", row.get("intentId"));
      event.put("attemptId", row.get("requestKey"));
      event.put("orderId", row.get("orderId"));
      event.put("merchantId", row.get("merchantId"));
      event.put("providerId", row.get("providerId"));
      event.put("providerTransactionId", row.get("id"));
      event.put("amountMinor", row.get("amountMinor"));
      event.put("currency", row.get("currency"));
      event.put("status", status);
      event.put("occurredAt", Instant.now().toString());
      String payload = Json.write(event);
      db.exec(
        "UPDATE provider_transactions SET status=?,outcome_payload=?,completed_at=now() WHERE id=?",
        status,
        payload,
        row.get("id")
      );
      int copies = "DUPLICATE".equals(Json.text(row, "mode")) ? 3 : 1;
      int delay = "DELAY".equals(Json.text(row, "mode")) ? 20 : 0;
      db.exec(
        "INSERT INTO callback_jobs(id,payload,remaining,next_attempt_at) VALUES(?,?,?,now()+?*interval '1 second')",
        Json.id(),
        payload,
        copies,
        delay
      );
      return Map.of("status", status, "simulated", true);
    });
  }

  @GetMapping("/operations/providers/scenarios")
  public List<Map<String, Object>> scenarios(Authentication auth) {
    Actor.of(auth).requireOperations();
    return db.rows("SELECT * FROM scenarios ORDER BY provider_id");
  }

  @PutMapping("/operations/providers/{provider}/scenario")
  public Map<String, Object> scenario(
    @PathVariable String provider,
    @RequestBody Map<String, Object> body,
    Authentication auth
  ) {
    Actor.of(auth).requireOperations();
    String mode = Json.text(body, "mode");
    if (
      !Set.of("NORMAL", "DUPLICATE", "DELAY", "TIMEOUT", "FAILURE").contains(
        mode
      )
    ) throw ApiError.bad("Unsupported scenario");
    if (
      db.exec(
        "UPDATE scenarios SET mode=? WHERE provider_id=?",
        mode,
        provider
      ) == 0
    ) throw ApiError.notFound();
    return Map.of("updated", true);
  }

  @PostMapping("/operations/providers/payments/{id}/replay")
  public Map<String, Object> replay(
    @PathVariable String id,
    Authentication auth
  ) {
    var actor = Actor.of(auth);
    actor.requireOperations();
    var row = db.one(
      "SELECT * FROM provider_transactions WHERE intent_id=? AND merchant_id=?",
      id,
      actor.merchantId()
    );
    if (row.get("outcomePayload") == null) throw ApiError.conflict(
      "Payment has no final outcome to replay"
    );
    db.exec(
      "INSERT INTO callback_jobs(id,payload,remaining) VALUES(?,?,3)",
      Json.id(),
      row.get("outcomePayload")
    );
    return Map.of("queued", 3);
  }

  @Scheduled(fixedDelay = 1000)
  public void callbacks() {
    try {
      var jobs = db.tx(() -> {
        var rows = db.rows(
          "SELECT * FROM callback_jobs WHERE remaining>0 AND next_attempt_at<=now() ORDER BY next_attempt_at LIMIT 10 FOR UPDATE SKIP LOCKED"
        );
        for (var row : rows)
          db.exec(
            "UPDATE callback_jobs SET next_attempt_at=now()+interval '10 seconds' WHERE id=?",
            row.get("id")
          );
        return rows;
      });
      for (var job : jobs) {
        String payload = Json.text(job, "payload"),
          timestamp = Long.toString(Instant.now().getEpochSecond());
        var e = Json.read(payload);
        try {
          client
            .post()
            .uri(callback + "/webhooks/" + e.get("providerId"))
            .header("X-Webhook-Timestamp", timestamp)
            .header(
              "X-Webhook-Signature",
              Json.sign(secret, timestamp, payload)
            )
            .header("Content-Type", "application/json")
            .body(payload)
            .retrieve()
            .toBodilessEntity();
          db.exec(
            "UPDATE callback_jobs SET remaining=remaining-1,attempts=attempts+1,last_error=NULL,next_attempt_at=now()+interval '1 second' WHERE id=?",
            job.get("id")
          );
        } catch (Exception failure) {
          db.exec(
            "UPDATE callback_jobs SET attempts=attempts+1,last_error=?,next_attempt_at=now()+interval '5 seconds' WHERE id=?",
            failure.getClass().getSimpleName(),
            job.get("id")
          );
        }
      }
    } catch (Exception e) {
      org.slf4j.LoggerFactory.getLogger(getClass()).warn(
        "Callback jobs will retry: {}",
        e.getMessage()
      );
    }
  }
}
