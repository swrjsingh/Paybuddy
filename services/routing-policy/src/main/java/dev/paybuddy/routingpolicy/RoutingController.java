package dev.paybuddy.routingpolicy;

import dev.paybuddy.common.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class RoutingController {

  private final Db db;

  public RoutingController(Db db) {
    this.db = db;
  }

  private List<Map<String, Object>> providers(String merchant) {
    return db.rows(
      "SELECT p.id,p.name,p.method,coalesce(m.priority,p.priority) AS priority,coalesce(m.enabled,p.enabled) AS enabled FROM providers p LEFT JOIN policies m ON m.provider_id=p.id AND m.merchant_id=? ORDER BY priority,p.id",
      merchant
    );
  }

  @GetMapping("/providers")
  public List<Map<String, Object>> list(Authentication auth) {
    return providers(Actor.of(auth).merchantId());
  }

  @PostMapping("/internal/routes/select")
  public Map<String, Object> select(@RequestBody Map<String, Object> body) {
    var eligible = providers(Json.text(body, "merchantId"))
      .stream()
      .filter(
        p ->
          Boolean.TRUE.equals(p.get("enabled")) &&
          Json.text(p, "method").equals(Json.text(body, "method"))
      )
      .toList();
    if (eligible.isEmpty()) throw new ApiError(
      org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
      "No eligible provider for this method"
    );
    var provider = new LinkedHashMap<>(eligible.getFirst());
    provider.put(
      "reason",
      "Selected the enabled " +
        provider.get("method") +
        " provider with priority " +
        provider.get("priority")
    );
    return provider;
  }

  @PutMapping("/routing/policies/{providerId}")
  public Map<String, Object> update(
    @PathVariable String providerId,
    @RequestBody Map<String, Object> body,
    Authentication auth
  ) {
    var actor = Actor.of(auth);
    actor.requireOperations();
    db.one("SELECT * FROM providers WHERE id=?", providerId);
    long priority = Json.number(body, "priority");
    if (
      priority < 1 ||
      priority > 1000 ||
      !(body.get("enabled") instanceof Boolean)
    ) throw ApiError.bad("Provide priority 1–1000 and enabled true/false");
    db.exec(
      "INSERT INTO policies VALUES(?,?,?,?) ON CONFLICT(merchant_id,provider_id) DO UPDATE SET priority=excluded.priority,enabled=excluded.enabled",
      actor.merchantId(),
      providerId,
      priority,
      body.get("enabled")
    );
    return Map.of("updated", true);
  }
}
