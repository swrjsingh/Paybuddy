package dev.paybuddy.webhookingestion;

import dev.paybuddy.common.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class WebhookController {

  private final Db db;
  private final String secret;

  public WebhookController(
    Db db,
    @Value("${paybuddy.webhook-secret}") String secret
  ) {
    this.db = db;
    this.secret = secret;
  }

  @PostMapping("/webhooks/{provider}")
  public Map<String, Object> ingest(
    @PathVariable String provider,
    @RequestHeader(
      value = "X-Webhook-Timestamp",
      required = false
    ) String timestamp,
    @RequestHeader(
      value = "X-Webhook-Signature",
      required = false
    ) String signature,
    @RequestBody String raw
  ) {
    if (raw.length() > 16384) throw ApiError.bad("Webhook exceeds size limit");
    long time;
    try {
      time = Long.parseLong(timestamp);
    } catch (Exception e) {
      throw new ApiError(
        HttpStatus.UNAUTHORIZED,
        "Missing/invalid webhook signature"
      );
    }
    if (
      time < Instant.now().getEpochSecond() - 300 ||
      time > Instant.now().getEpochSecond() + 300 ||
      signature == null ||
      !Json.same(Json.sign(secret, timestamp, raw), signature)
    ) throw new ApiError(
      HttpStatus.UNAUTHORIZED,
      "Invalid or expired webhook signature"
    );
    var event = Json.read(raw);
    for (String field : List.of(
      "eventId",
      "intentId",
      "attemptId",
      "orderId",
      "merchantId",
      "providerId",
      "providerTransactionId",
      "currency",
      "status"
    )) {
      if (
        Json.text(event, field).isBlank() ||
        Json.text(event, field).length() > 150
      ) throw ApiError.bad("Missing/invalid " + field);
    }
    if (
      !provider.equals(Json.text(event, "providerId")) ||
      !Set.of("PROCESSING", "SUCCEEDED", "FAILED").contains(
        Json.text(event, "status")
      ) ||
      Json.number(event, "amountMinor") <= 0 ||
      !"INR".equals(Json.text(event, "currency"))
    ) throw ApiError.bad("Invalid provider outcome");
    return db.tx(() -> {
      String id = Json.text(event, "eventId"),
        hash = Json.hash(raw);
      int inserted = db.exec(
        "INSERT INTO webhook_events(provider_id,event_id,merchant_id,intent_id,payload,payload_hash) VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING",
        provider,
        id,
        event.get("merchantId"),
        event.get("intentId"),
        raw,
        hash
      );
      var existing = db.one(
        "SELECT * FROM webhook_events WHERE provider_id=? AND event_id=? FOR UPDATE",
        provider,
        id
      );
      if (
        !hash.equals(Json.text(existing, "payloadHash"))
      ) throw ApiError.conflict("Event ID reused with different content");
      if (inserted == 0) db.exec(
        "UPDATE webhook_events SET deliveries=deliveries+1 WHERE provider_id=? AND event_id=?",
        provider,
        id
      );
      else db.event(provider + "-" + id, "ProviderOutcomeObserved", event);
      return Map.of("accepted", true, "duplicate", inserted == 0);
    });
  }

  @GetMapping("/operations/webhooks")
  public List<Map<String, Object>> list(Authentication auth) {
    var actor = Actor.of(auth);
    actor.requireOperations();
    return db.rows(
      "SELECT provider_id,event_id,intent_id,deliveries,received_at FROM webhook_events WHERE merchant_id=? ORDER BY received_at DESC LIMIT 100",
      actor.merchantId()
    );
  }
}
