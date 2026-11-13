package dev.paybuddy.ledgerreconciliation;

import dev.paybuddy.common.*;
import java.util.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class LedgerController {

  private final Db db;

  public LedgerController(Db db) {
    this.db = db;
  }

  @GetMapping("/ledger")
  public List<Map<String, Object>> list(Authentication auth) {
    return db.rows(
      "SELECT * FROM ledger_entries WHERE merchant_id=? ORDER BY created_at DESC LIMIT 200",
      Actor.of(auth).merchantId()
    );
  }

  @GetMapping("/ledger/{id}")
  public List<Map<String, Object>> get(
    @PathVariable String id,
    Authentication auth
  ) {
    return db.rows(
      "SELECT * FROM ledger_entries WHERE payment_intent_id=? AND merchant_id=? ORDER BY created_at",
      id,
      Actor.of(auth).merchantId()
    );
  }

  @RabbitListener(queues = "ledger.payments")
  public void append(String raw) {
    var e = Json.read(raw);
    db.tx(() -> {
      if (!db.first(Json.text(e, "eventId"))) return null;
      db.exec(
        "INSERT INTO ledger_entries(id,payment_intent_id,order_id,merchant_id,provider_id,provider_transaction_id,entry_kind,amount_minor,currency) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(payment_intent_id,entry_kind) DO NOTHING",
        Json.id(),
        e.get("intentId"),
        e.get("orderId"),
        e.get("merchantId"),
        e.get("providerId"),
        e.get("providerTransactionId"),
        "PAYMENT_SUCCEEDED",
        Json.number(e, "amountMinor"),
        e.get("currency")
      );
      return null;
    });
  }
}
