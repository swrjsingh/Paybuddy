package dev.paybuddy.order;

import dev.paybuddy.common.*;
import java.util.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class OrderController {

  private final Db db;

  public OrderController(Db db) {
    this.db = db;
  }

  @PostMapping("/orders")
  public Map<String, Object> create(
    @RequestBody Map<String, Object> body,
    Authentication auth
  ) {
    var actor = Actor.of(auth);
    long amount = Json.number(body, "amountMinor");
    String description = Json.text(body, "description").trim();
    if (amount <= 0 || amount > 100000000) throw ApiError.bad(
      "Amount must be between ₹0.01 and ₹10,00,000"
    );
    if (description.isEmpty() || description.length() > 160) throw ApiError.bad(
      "Description must contain 1–160 characters"
    );
    if (
      body.containsKey("currency") && !"INR".equals(Json.text(body, "currency"))
    ) throw ApiError.bad("Only INR is supported");
    String id = Json.id();
    db.exec(
      "INSERT INTO orders(id,merchant_id,description,amount_minor,currency) VALUES(?,?,?,?,?)",
      id,
      actor.merchantId(),
      description,
      amount,
      "INR"
    );
    return db.one("SELECT * FROM orders WHERE id=?", id);
  }

  @GetMapping("/orders")
  public List<Map<String, Object>> list(Authentication auth) {
    return db.rows(
      "SELECT o.*,p.payment_intent_id FROM orders o LEFT JOIN order_payments p ON p.order_id=o.id WHERE o.merchant_id=? ORDER BY o.created_at DESC LIMIT 200",
      Actor.of(auth).merchantId()
    );
  }

  @GetMapping("/orders/{id}")
  public Map<String, Object> get(@PathVariable String id, Authentication auth) {
    return db.one(
      "SELECT o.*,p.payment_intent_id FROM orders o LEFT JOIN order_payments p ON p.order_id=o.id WHERE o.id=? AND o.merchant_id=?",
      id,
      Actor.of(auth).merchantId()
    );
  }

  @GetMapping("/internal/orders/{id}")
  public Map<String, Object> internal(@PathVariable String id) {
    return db.one("SELECT * FROM orders WHERE id=?", id);
  }

  @RabbitListener(queues = "order.payments")
  public void paid(String raw) {
    var e = Json.read(raw);
    db.tx(() -> {
      if (!db.first(Json.text(e, "eventId"))) return null;
      var order = db.one(
        "SELECT * FROM orders WHERE id=? FOR UPDATE",
        Json.text(e, "orderId")
      );
      if (
        !Json.text(order, "merchantId").equals(Json.text(e, "merchantId")) ||
        Json.number(order, "amountMinor") != Json.number(e, "amountMinor") ||
        !Json.text(order, "currency").equals(Json.text(e, "currency"))
      ) throw ApiError.conflict("Payment does not match order");
      var associations = db.rows(
        "SELECT * FROM order_payments WHERE order_id=?",
        order.get("id")
      );
      if (
        !associations.isEmpty() &&
        !Json.text(associations.getFirst(), "paymentIntentId").equals(
          Json.text(e, "intentId")
        )
      ) throw ApiError.conflict("Order already paid by another intent");
      db.exec(
        "INSERT INTO order_payments(order_id,payment_intent_id,provider_transaction_id) VALUES(?,?,?) ON CONFLICT(order_id) DO NOTHING",
        order.get("id"),
        e.get("intentId"),
        e.get("providerTransactionId")
      );
      db.exec(
        "UPDATE orders SET status='PAID',paid_at=coalesce(paid_at,now()) WHERE id=?",
        order.get("id")
      );
      return null;
    });
  }
}
