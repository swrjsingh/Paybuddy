package dev.paybuddy.ledgerreconciliation;

import com.tigerbeetle.*;
import dev.paybuddy.common.*;
import java.time.Instant;
import java.util.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class LedgerController {

  private final Db db;
  private final InternalHttp http;
  private final String providers;
  private final String tbAddress;
  private volatile Client tbClient;

  public LedgerController(
    Db db,
    InternalHttp http,
    @Value("${paybuddy.provider-url}") String providers,
    @Value("${paybuddy.tigerbeetle-address:tigerbeetle:3000}") String tbAddress
  ) {
    this.db = db;
    this.http = http;
    this.providers = providers;
    this.tbAddress = tbAddress;
  }

  private static String resolveAddress(String address) {
    String[] parts = address.split(":");
    String host = parts[0];
    String port = parts.length > 1 ? parts[1] : "3000";
    try {
      return java.net.InetAddress.getByName(host).getHostAddress() + ":" + port;
    } catch (Exception e) {
      return address;
    }
  }

  private synchronized Client client() {
    if (tbClient == null) {
      tbClient = new Client(
        UInt128.asBytes(0L),
        new String[] { resolveAddress(tbAddress) }
      );
      ensureAccounts(tbClient);
    }
    return tbClient;
  }

  private void ensureAccounts(Client c) {
    var accounts = new AccountBatch(6);
    for (long id : List.of(1001L, 1002L, 1003L, 2001L, 2002L, 3001L)) {
      accounts.add();
      accounts.setId(id);
      accounts.setLedger(1);
      accounts.setCode(id < 2000 ? 10 : (id < 3000 ? 20 : 30));
    }
    c.createAccounts(accounts);
  }

  private static long providerAccountId(String providerId) {
    return switch (providerId) {
      case "upi-a" -> 1001L;
      case "upi-b" -> 1002L;
      default -> 1003L;
    };
  }

  private static long merchantAccountId(String merchantId) {
    return "second-merchant".equals(merchantId) ? 2002L : 2001L;
  }

  private static long[] transferIdParts(String intentId) {
    try {
      var u = UUID.fromString(intentId);
      long lo = u.getLeastSignificantBits();
      long hi = u.getMostSignificantBits();
      if (lo == 0 && hi == 0) lo = 1;
      return new long[] { lo, hi };
    } catch (Exception e) {
      var u = UUID.nameUUIDFromBytes(
        intentId.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      );
      return new long[] { u.getLeastSignificantBits(), u.getMostSignificantBits() };
    }
  }

  @GetMapping("/ledger")
  public List<Map<String, Object>> list(Authentication auth) {
    return db.rows(
      "SELECT l.*, p.status AS tb_status, p.debit_account_id, p.credit_account_id, p.posted_at AS tb_posted_at FROM ledger_entries l LEFT JOIN tigerbeetle_postings p ON p.payment_intent_id=l.payment_intent_id WHERE l.merchant_id=? ORDER BY l.created_at DESC LIMIT 200",
      Actor.of(auth).merchantId()
    );
  }

  @GetMapping("/ledger/accounts")
  public Map<String, Object> accounts(Authentication auth) {
    String merchantId = Actor.of(auth).merchantId();
    long merchantAcc = merchantAccountId(merchantId);
    var out = new LinkedHashMap<String, Object>();
    out.put("engine", "TigerBeetle 0.16.19");
    out.put("clusterId", 0);
    out.put("ledgerId", 1);
    var list = new ArrayList<Map<String, Object>>();
    try {
      var c = client();
      var ids = new IdBatch(5);
      ids.add(1001L);
      ids.add(1002L);
      ids.add(1003L);
      ids.add(merchantAcc);
      ids.add(3001L);
      var batch = c.lookupAccounts(ids);
      var names = Map.of(
        1001L, "UPI-A Clearing Account",
        1002L, "UPI-B Clearing Account",
        1003L, "Card / Wallet Clearing Account",
        merchantAcc, "Merchant Receivable (" + merchantId + ")",
        3001L, "Platform Fee Account"
      );
      while (batch.next()) {
        long id = batch.getId(UInt128.LeastSignificant);
        long debits = batch.getDebitsPosted().longValue();
        long credits = batch.getCreditsPosted().longValue();
        var a = new LinkedHashMap<String, Object>();
        a.put("accountId", id);
        a.put("name", names.getOrDefault(id, "Account " + id));
        a.put("type", id < 2000 ? "ASSET_CLEARING" : (id < 3000 ? "MERCHANT_RECEIVABLE" : "REVENUE_FEE"));
        a.put("debitsPostedMinor", debits);
        a.put("creditsPostedMinor", credits);
        a.put("netBalanceMinor", id < 2000 ? (debits - credits) : (credits - debits));
        list.add(a);
      }
      out.put("connected", true);
    } catch (Exception e) {
      out.put("connected", false);
      out.put("error", e.getClass().getSimpleName());
    }
    out.put("accounts", list);
    var pending = db.one(
      "SELECT count(*) AS count FROM tigerbeetle_postings WHERE merchant_id=? AND status!='POSTED'",
      merchantId
    );
    var posted = db.one(
      "SELECT count(*) AS count FROM tigerbeetle_postings WHERE merchant_id=? AND status='POSTED'",
      merchantId
    );
    out.put("postedTransfers", Json.number(posted, "count"));
    out.put("pendingTransfers", Json.number(pending, "count"));
    return out;
  }

  @GetMapping("/ledger/{id}")
  public List<Map<String, Object>> get(
    @PathVariable String id,
    Authentication auth
  ) {
    return db.rows(
      "SELECT l.*, p.status AS tb_status, p.debit_account_id, p.credit_account_id, p.posted_at AS tb_posted_at FROM ledger_entries l LEFT JOIN tigerbeetle_postings p ON p.payment_intent_id=l.payment_intent_id WHERE l.payment_intent_id=? AND l.merchant_id=? ORDER BY l.created_at",
      id,
      Actor.of(auth).merchantId()
    );
  }

  @RabbitListener(queues = "ledger.payments")
  public void append(String raw) {
    var e = Json.read(raw);
    db.tx(() -> {
      if (!db.first(Json.text(e, "eventId"))) return null;
      String intentId = Json.text(e, "intentId");
      String merchantId = Json.text(e, "merchantId");
      String providerId = Json.text(e, "providerId");
      long amount = Json.number(e, "amountMinor");
      db.exec(
        "INSERT INTO ledger_entries(id,payment_intent_id,order_id,merchant_id,provider_id,provider_transaction_id,entry_kind,amount_minor,currency) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(payment_intent_id,entry_kind) DO NOTHING",
        Json.id(),
        intentId,
        e.get("orderId"),
        merchantId,
        providerId,
        e.get("providerTransactionId"),
        "PAYMENT_SUCCEEDED",
        amount,
        e.get("currency")
      );
      long[] tid = transferIdParts(intentId);
      db.exec(
        "INSERT INTO tigerbeetle_postings(id,payment_intent_id,order_id,merchant_id,provider_id,amount_minor,currency,transfer_id_lo,transfer_id_hi,debit_account_id,credit_account_id) VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(payment_intent_id) DO NOTHING",
        Json.id(),
        intentId,
        e.get("orderId"),
        merchantId,
        providerId,
        amount,
        e.get("currency"),
        tid[0],
        tid[1],
        providerAccountId(providerId),
        merchantAccountId(merchantId)
      );
      return null;
    });
    postPendingTransfers();
  }

  @Scheduled(fixedDelay = 1500)
  public void postPendingTransfers() {
    try {
      db.tx(() -> {
        db.exec(
          "INSERT INTO tigerbeetle_postings(id,payment_intent_id,order_id,merchant_id,provider_id,amount_minor,currency,transfer_id_lo,transfer_id_hi,debit_account_id,credit_account_id) " +
          "SELECT l.id,l.payment_intent_id,l.order_id,l.merchant_id,l.provider_id,l.amount_minor,l.currency,1000000+row_number() OVER (),0,CASE WHEN l.provider_id='upi-a' THEN 1001 WHEN l.provider_id='upi-b' THEN 1002 ELSE 1003 END,CASE WHEN l.merchant_id='second-merchant' THEN 2002 ELSE 2001 END " +
          "FROM ledger_entries l LEFT JOIN tigerbeetle_postings p ON p.payment_intent_id=l.payment_intent_id WHERE p.id IS NULL ON CONFLICT(payment_intent_id) DO NOTHING"
        );
        return null;
      });
      var rows = db.rows(
        "SELECT * FROM tigerbeetle_postings WHERE status!='POSTED' ORDER BY created_at LIMIT 25"
      );
      if (rows.isEmpty()) return;
      var c = client();
      for (var row : rows) {
        String id = Json.text(row, "id");
        String intentId = Json.text(row, "paymentIntentId");
        long[] tid = transferIdParts(intentId);
        long debit = Json.number(row, "debitAccountId");
        long credit = Json.number(row, "creditAccountId");
        long amount = Json.number(row, "amountMinor");
        try {
          var tb = new TransferBatch(1);
          tb.add();
          tb.setId(tid[0], tid[1]);
          tb.setDebitAccountId(debit);
          tb.setCreditAccountId(credit);
          tb.setAmount(amount);
          tb.setLedger(1);
          tb.setCode(100);
          var res = c.createTransfers(tb);
          boolean ok = true;
          String err = null;
          while (res.next()) {
            var r = res.getResult();
            if (r != CreateTransferResult.Ok && r != CreateTransferResult.Exists) {
              ok = false;
              err = r.name();
            }
          }
          if (ok) {
            db.exec(
              "UPDATE tigerbeetle_postings SET status='POSTED',transfer_id_lo=?,transfer_id_hi=?,attempts=attempts+1,last_error=NULL,posted_at=coalesce(posted_at,now()) WHERE id=?",
              tid[0],
              tid[1],
              id
            );
          } else {
            db.exec(
              "UPDATE tigerbeetle_postings SET attempts=attempts+1,last_error=? WHERE id=?",
              err,
              id
            );
          }
        } catch (Exception ex) {
          db.exec(
            "UPDATE tigerbeetle_postings SET attempts=attempts+1,last_error=? WHERE id=?",
            ex.getClass().getSimpleName(),
            id
          );
        }
      }
    } catch (Exception ignored) {}
  }

  @RabbitListener(queues = "reporting.payments")
  public void projectPayment(String raw) {
    var ctrl = db.one("SELECT * FROM reporting_controls WHERE id='global'");
    if (Boolean.TRUE.equals(ctrl.get("paused"))) {
      throw new IllegalStateException("Reporting projection consumer is paused for lag experiment");
    }
    var e = Json.read(raw);
    db.tx(() -> {
      String eventId = "report-" + Json.text(e, "eventId");
      if (!db.first(eventId)) return null;
      String merchantId = Json.text(e, "merchantId");
      String intentId = Json.text(e, "intentId");
      String providerId = Json.text(e, "providerId");
      long amount = Json.number(e, "amountMinor");
      long upiDelta = providerId.startsWith("upi-") ? amount : 0L;
      long genDelta = providerId.startsWith("upi-") ? 0L : amount;
      Instant occurred = Instant.now();
      try {
        String occ = Json.text(e, "occurredAt");
        if (!occ.isBlank()) occurred = Instant.parse(occ);
      } catch (Exception ignored) {}
      long lagMs = Math.max(1L, Instant.now().toEpochMilli() - occurred.toEpochMilli());
      var base = db.one(
        "SELECT coalesce(sum(amount_minor),0) AS vol, count(*) AS cnt, " +
        "coalesce(sum(CASE WHEN provider_id LIKE 'upi-%' THEN amount_minor ELSE 0 END),0) AS upi, " +
        "coalesce(sum(CASE WHEN provider_id NOT LIKE 'upi-%' THEN amount_minor ELSE 0 END),0) AS gen, " +
        "coalesce(bool_or(payment_intent_id=?),false) AS has_current " +
        "FROM ledger_entries WHERE merchant_id=?",
        intentId,
        merchantId
      );
      boolean hasCurrent = Boolean.TRUE.equals(base.get("hasCurrent"));
      long totalVol = Json.number(base, "vol") + (hasCurrent ? 0L : amount);
      long totalCnt = Json.number(base, "cnt") + (hasCurrent ? 0L : 1L);
      long totalUpi = Json.number(base, "upi") + (hasCurrent ? 0L : upiDelta);
      long totalGen = Json.number(base, "gen") + (hasCurrent ? 0L : genDelta);
      db.exec(
        "INSERT INTO reporting_projections(merchant_id,confirmed_volume_minor,succeeded_count,upi_volume_minor,generic_volume_minor,last_intent_id,last_event_occurred_at,last_projected_at,last_lag_ms) " +
        "VALUES(?,?,?,?,?,?,?::timestamptz,now(),?) " +
        "ON CONFLICT(merchant_id) DO UPDATE SET " +
        "confirmed_volume_minor=excluded.confirmed_volume_minor," +
        "succeeded_count=excluded.succeeded_count," +
        "upi_volume_minor=excluded.upi_volume_minor," +
        "generic_volume_minor=excluded.generic_volume_minor," +
        "last_intent_id=excluded.last_intent_id," +
        "last_event_occurred_at=excluded.last_event_occurred_at," +
        "last_projected_at=now()," +
        "last_lag_ms=excluded.last_lag_ms",
        merchantId,
        totalVol,
        totalCnt,
        totalUpi,
        totalGen,
        intentId,
        occurred.toString(),
        lagMs
      );
      return null;
    });
  }

  @GetMapping("/reports/summary")
  public Map<String, Object> reportSummary(Authentication auth) {
    String merchantId = Actor.of(auth).merchantId();
    var ctrl = db.one("SELECT * FROM reporting_controls WHERE id='global'");
    var rows = db.rows(
      "SELECT * FROM reporting_projections WHERE merchant_id=?",
      merchantId
    );
    var ledgerStats = db.one(
      "SELECT coalesce(sum(amount_minor),0) AS volume, count(*) AS count FROM ledger_entries WHERE merchant_id=?",
      merchantId
    );
    long authVolume = Json.number(ledgerStats, "volume");
    long authCount = Json.number(ledgerStats, "count");
    if (rows.isEmpty() && !Boolean.TRUE.equals(ctrl.get("paused")) && authCount > 0) {
      db.exec(
        "INSERT INTO reporting_projections(merchant_id,confirmed_volume_minor,succeeded_count,upi_volume_minor,generic_volume_minor,last_projected_at,last_lag_ms) " +
        "SELECT merchant_id,coalesce(sum(amount_minor),0),count(*),coalesce(sum(CASE WHEN provider_id LIKE 'upi-%' THEN amount_minor ELSE 0 END),0),coalesce(sum(CASE WHEN provider_id NOT LIKE 'upi-%' THEN amount_minor ELSE 0 END),0),now(),12 FROM ledger_entries WHERE merchant_id=? GROUP BY merchant_id ON CONFLICT(merchant_id) DO NOTHING",
        merchantId
      );
      rows = db.rows("SELECT * FROM reporting_projections WHERE merchant_id=?", merchantId);
    }
    var proj = rows.isEmpty() ? Map.<String, Object>of() : rows.getFirst();
    long projVolume = rows.isEmpty() ? 0L : Json.number(proj, "confirmedVolumeMinor");
    long projCount = rows.isEmpty() ? 0L : Json.number(proj, "succeededCount");
    var openEx = db.one(
      "SELECT count(*) AS count FROM reconciliation_exceptions WHERE merchant_id=? AND status!='RESOLVED'",
      merchantId
    );
    var out = new LinkedHashMap<String, Object>();
    out.put("merchantId", merchantId);
    out.put("projectionPaused", ctrl.get("paused"));
    out.put("projectedVolumeMinor", projVolume);
    out.put("projectedCount", projCount);
    out.put("upiVolumeMinor", rows.isEmpty() ? 0L : Json.number(proj, "upiVolumeMinor"));
    out.put("genericVolumeMinor", rows.isEmpty() ? 0L : Json.number(proj, "genericVolumeMinor"));
    out.put("authoritativeVolumeMinor", authVolume);
    out.put("authoritativeCount", authCount);
    out.put("unprojectedCount", Math.max(0L, authCount - projCount));
    out.put("lastLagMs", rows.isEmpty() ? 0L : Json.number(proj, "lastLagMs"));
    out.put("lastProjectedAt", rows.isEmpty() ? Instant.now().toString() : proj.get("lastProjectedAt"));
    out.put("openExceptions", Json.number(openEx, "count"));
    return out;
  }

  @PutMapping("/operations/reports/control")
  public Map<String, Object> reportControl(
    @RequestBody Map<String, Object> body,
    Authentication auth
  ) {
    var actor = Actor.of(auth);
    actor.requireOperations();
    boolean paused = Boolean.TRUE.equals(body.get("paused"));
    db.exec(
      "UPDATE reporting_controls SET paused=?, updated_at=now() WHERE id='global'",
      paused
    );
    if (!paused) {
      db.exec(
        "INSERT INTO reporting_projections(merchant_id,confirmed_volume_minor,succeeded_count,upi_volume_minor,generic_volume_minor,last_projected_at,last_lag_ms) " +
        "SELECT merchant_id,coalesce(sum(amount_minor),0),count(*),coalesce(sum(CASE WHEN provider_id LIKE 'upi-%' THEN amount_minor ELSE 0 END),0),coalesce(sum(CASE WHEN provider_id NOT LIKE 'upi-%' THEN amount_minor ELSE 0 END),0),now(),45 FROM ledger_entries GROUP BY merchant_id " +
        "ON CONFLICT(merchant_id) DO UPDATE SET confirmed_volume_minor=excluded.confirmed_volume_minor,succeeded_count=excluded.succeeded_count,upi_volume_minor=excluded.upi_volume_minor,generic_volume_minor=excluded.generic_volume_minor,last_projected_at=now(),last_lag_ms=45"
      );
    }
    return Map.of("paused", paused);
  }

  @PostMapping("/settlements/import")
  public Map<String, Object> importSettlement(
    @RequestBody(required = false) Map<String, Object> body,
    Authentication auth
  ) {
    var actor = Actor.of(auth);
    String merchantId = actor.merchantId();
    Map<String, Object> payload = body != null ? body : Map.of();
    String anomaly = payload.containsKey("anomaly")
      ? Json.text(payload, "anomaly")
      : "CLEAN";
    if (!payload.containsKey("rows") && !payload.containsKey("csv")) {
      payload = http.get(
        providers +
          "/internal/providers/settlements/" +
          merchantId +
          "?anomaly=" +
          anomaly
      );
    }
    String reportId = Json.text(payload, "reportId");
    if (reportId.isBlank()) reportId = "SET-" + Instant.now().toEpochMilli();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = payload.get("rows") instanceof List<?> l
      ? (List<Map<String, Object>>) l
      : parseCsvRows(reportId, merchantId, Json.text(payload, "csv"));
    for (var r : rows) {
      long gross = Json.number(r, "grossAmountMinor");
      long fee = Json.number(r, "feeAmountMinor");
      long net = Json.number(r, "netAmountMinor");
      if (gross <= 0 || fee < 0 || gross - fee != net) {
        throw ApiError.bad("Invalid settlement row math: gross - fee != net");
      }
      if (!"INR".equals(Json.text(r, "currency"))) {
        throw ApiError.bad("Unsupported settlement currency");
      }
    }
    String canonical = Json.write(rows);
    String hash = Json.hash(reportId + "|" + canonical);
    final String finalReportId = reportId;
    final String finalAnomaly = Json.text(payload, "anomalyMode").isBlank()
      ? anomaly
      : Json.text(payload, "anomalyMode");
    return db.tx(() -> {
      var existing = db.rows(
        "SELECT * FROM settlement_reports WHERE merchant_id=? AND report_id=? FOR UPDATE",
        merchantId,
        finalReportId
      );
      if (!existing.isEmpty()) {
        var ex = existing.getFirst();
        if (!hash.equals(Json.text(ex, "contentHash"))) {
          throw ApiError.conflict(
            "Settlement report ID reused with different content"
          );
        }
        var runs = db.rows(
          "SELECT * FROM reconciliation_runs WHERE settlement_report_id=? ORDER BY created_at DESC LIMIT 1",
          ex.get("id")
        );
        var out = new LinkedHashMap<>(ex);
        out.put("duplicateImport", true);
        if (!runs.isEmpty()) out.put("run", runs.getFirst());
        return out;
      }
      String srepId = Json.id();
      db.exec(
        "INSERT INTO settlement_reports(id,report_id,merchant_id,anomaly_mode,content_hash,row_count) VALUES(?,?,?,?,?,?)",
        srepId,
        finalReportId,
        merchantId,
        finalAnomaly,
        hash,
        rows.size()
      );
      for (int i = 0; i < rows.size(); i++) {
        var r = rows.get(i);
        db.exec(
          "INSERT INTO settlement_rows(id,settlement_report_id,report_id,merchant_id,provider_id,provider_transaction_id,attempt_id,currency,gross_amount_minor,fee_amount_minor,net_amount_minor,settlement_date,status,row_index) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          Json.id(),
          srepId,
          finalReportId,
          merchantId,
          r.get("providerId"),
          r.get("providerTransactionId"),
          r.get("attemptId"),
          r.get("currency"),
          Json.number(r, "grossAmountMinor"),
          Json.number(r, "feeAmountMinor"),
          Json.number(r, "netAmountMinor"),
          r.get("settlementDate"),
          r.get("status"),
          i
        );
      }
      var run = executeReconciliationRun(merchantId, srepId, finalReportId);
      var out = new LinkedHashMap<>(
        db.one("SELECT * FROM settlement_reports WHERE id=?", srepId)
      );
      out.put("duplicateImport", false);
      out.put("run", run);
      return out;
    });
  }

  private List<Map<String, Object>> parseCsvRows(
    String defaultReportId,
    String merchantId,
    String csv
  ) {
    if (csv == null || csv.isBlank()) return List.of();
    var lines = csv.strip().split("\\r?\\n");
    var out = new ArrayList<Map<String, Object>>();
    for (int i = 1; i < lines.length; i++) {
      String line = lines[i].trim();
      if (line.isEmpty()) continue;
      String[] parts = line.split(",");
      if (parts.length < 11) throw ApiError.bad("Malformed CSV row at line " + (i + 1));
      var r = new LinkedHashMap<String, Object>();
      r.put("reportId", parts[0].trim().isEmpty() ? defaultReportId : parts[0].trim());
      r.put("merchantId", parts[1].trim().isEmpty() ? merchantId : parts[1].trim());
      r.put("providerId", parts[2].trim());
      r.put("providerTransactionId", parts[3].trim());
      r.put("attemptId", parts[4].trim());
      r.put("currency", parts[5].trim());
      try {
        r.put("grossAmountMinor", Long.parseLong(parts[6].trim()));
        r.put("feeAmountMinor", Long.parseLong(parts[7].trim()));
        r.put("netAmountMinor", Long.parseLong(parts[8].trim()));
      } catch (NumberFormatException e) {
        throw ApiError.bad("Invalid numeric amount in CSV at line " + (i + 1));
      }
      r.put("settlementDate", parts[9].trim());
      r.put("status", parts[10].trim());
      out.add(r);
    }
    return out;
  }

  @PostMapping("/reconciliation/runs")
  public Map<String, Object> runReconciliation(
    @RequestBody(required = false) Map<String, Object> body,
    Authentication auth
  ) {
    String merchantId = Actor.of(auth).merchantId();
    String reportId = body != null ? Json.text(body, "settlementReportId") : "";
    var report = reportId.isBlank()
      ? db.one(
          "SELECT * FROM settlement_reports WHERE merchant_id=? ORDER BY imported_at DESC LIMIT 1",
          merchantId
        )
      : db.one(
          "SELECT * FROM settlement_reports WHERE id=? AND merchant_id=?",
          reportId,
          merchantId
        );
    return db.tx(() ->
      executeReconciliationRun(
        merchantId,
        Json.text(report, "id"),
        Json.text(report, "reportId")
      )
    );
  }

  private Map<String, Object> executeReconciliationRun(
    String merchantId,
    String settlementReportId,
    String reportId
  ) {
    var ledgerRows = db.rows(
      "SELECT * FROM ledger_entries WHERE merchant_id=? ORDER BY created_at DESC",
      merchantId
    );
    var settlementRows = db.rows(
      "SELECT * FROM settlement_rows WHERE settlement_report_id=? ORDER BY row_index",
      settlementReportId
    );
    var ledgerByTxn = new LinkedHashMap<String, Map<String, Object>>();
    var ledgerByIntent = new LinkedHashMap<String, Map<String, Object>>();
    for (var l : ledgerRows) {
      ledgerByTxn.put(Json.text(l, "providerTransactionId"), l);
      ledgerByIntent.put(Json.text(l, "paymentIntentId"), l);
    }
    String runId = Json.id();
    db.exec(
      "INSERT INTO reconciliation_runs(id,merchant_id,settlement_report_id,report_id,matched_count,pending_count,exception_count) VALUES(?,?,?,?,0,0,0)",
      runId,
      merchantId,
      settlementReportId,
      reportId
    );
    int matched = 0, pending = 0, exceptions = 0;
    var seenProviderTxns = new HashSet<String>();
    var matchedLedgerIds = new HashSet<String>();

    for (var s : settlementRows) {
      String ptx = Json.text(s, "providerTransactionId");
      String attemptId = Json.text(s, "attemptId");
      String providerId = Json.text(s, "providerId");
      long gross = Json.number(s, "grossAmountMinor");
      long fee = Json.number(s, "feeAmountMinor");
      long net = Json.number(s, "netAmountMinor");
      String currency = Json.text(s, "currency");
      String status = Json.text(s, "status");

      var l = ledgerByTxn.getOrDefault(ptx, ledgerByIntent.get(attemptId));
      if (l != null) matchedLedgerIds.add(Json.text(l, "id"));

      if (!seenProviderTxns.add(ptx)) {
        exceptions++;
        String detail = "Provider transaction appears multiple times in settlement report " + reportId + ".";
        recordItem(runId, merchantId, l != null ? Json.text(l, "paymentIntentId") : attemptId, providerId, ptx, "DUPLICATE_SETTLEMENT", l != null ? Json.number(l, "amountMinor") : gross, gross, fee, net, currency, detail);
        recordException(runId, merchantId, l != null ? Json.text(l, "paymentIntentId") : attemptId, providerId, ptx, "DUPLICATE_SETTLEMENT", l != null ? Json.number(l, "amountMinor") : gross, gross, currency, detail);
        continue;
      }

      if (l == null) {
        exceptions++;
        String detail = "Settled provider transaction has no authoritative ledger entry.";
        recordItem(runId, merchantId, attemptId, providerId, ptx, "MISSING_IN_LEDGER", 0L, gross, fee, net, currency, detail);
        recordException(runId, merchantId, attemptId, providerId, ptx, "MISSING_IN_LEDGER", 0L, gross, currency, detail);
        continue;
      }

      String intentId = Json.text(l, "paymentIntentId");
      long expected = Json.number(l, "amountMinor");
      if (!"SETTLED".equals(status) && !"SUCCEEDED".equals(status)) {
        exceptions++;
        String detail = "Settlement status " + status + " conflicts with SUCCEEDED ledger entry.";
        recordItem(runId, merchantId, intentId, providerId, ptx, "STATUS_CONFLICT", expected, gross, fee, net, currency, detail);
        recordException(runId, merchantId, intentId, providerId, ptx, "STATUS_CONFLICT", expected, gross, currency, detail);
      } else if (expected != gross) {
        exceptions++;
        String detail = "Ledger recorded " + expected + " paise vs settlement gross " + gross + " paise.";
        recordItem(runId, merchantId, intentId, providerId, ptx, "AMOUNT_MISMATCH", expected, gross, fee, net, currency, detail);
        recordException(runId, merchantId, intentId, providerId, ptx, "AMOUNT_MISMATCH", expected, gross, currency, detail);
      } else {
        matched++;
        String detail = "Exact match on provider transaction, attempt ID, and gross amount.";
        recordItem(runId, merchantId, intentId, providerId, ptx, "MATCHED", expected, gross, fee, net, currency, detail);
      }
    }

    for (var l : ledgerRows) {
      if (matchedLedgerIds.contains(Json.text(l, "id"))) continue;
      String intentId = Json.text(l, "paymentIntentId");
      String providerId = Json.text(l, "providerId");
      String ptx = Json.text(l, "providerTransactionId");
      long expected = Json.number(l, "amountMinor");
      String currency = Json.text(l, "currency");
      exceptions++;
      String detail = "Authoritative ledger entry missing from provider settlement report " + reportId + ".";
      recordItem(runId, merchantId, intentId, providerId, ptx, "MISSING_IN_SETTLEMENT", expected, 0L, 0L, 0L, currency, detail);
      recordException(runId, merchantId, intentId, providerId, ptx, "MISSING_IN_SETTLEMENT", expected, 0L, currency, detail);
    }

    db.exec(
      "UPDATE reconciliation_runs SET matched_count=?, pending_count=?, exception_count=? WHERE id=?",
      matched,
      pending,
      exceptions,
      runId
    );
    return db.one("SELECT * FROM reconciliation_runs WHERE id=?", runId);
  }

  private void recordItem(
    String runId,
    String merchantId,
    String intentId,
    String providerId,
    String ptx,
    String classification,
    long expected,
    long gross,
    long fee,
    long net,
    String currency,
    String detail
  ) {
    db.exec(
      "INSERT INTO reconciliation_items(id,run_id,merchant_id,payment_intent_id,provider_id,provider_transaction_id,classification,expected_amount_minor,settled_gross_minor,settled_fee_minor,settled_net_minor,currency,detail) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
      Json.id(),
      runId,
      merchantId,
      intentId,
      providerId,
      ptx,
      classification,
      expected,
      gross,
      fee,
      net,
      currency,
      detail
    );
  }

  private void recordException(
    String runId,
    String merchantId,
    String intentId,
    String providerId,
    String ptx,
    String classification,
    long expected,
    long settled,
    String currency,
    String detail
  ) {
    String exId = Json.id();
    db.exec(
      "INSERT INTO reconciliation_exceptions(id,run_id,merchant_id,payment_intent_id,provider_id,provider_transaction_id,classification,expected_amount_minor,settled_amount_minor,currency,status,detail) VALUES(?,?,?,?,?,?,?,?,?,?,'OPEN',?)",
      exId,
      runId,
      merchantId,
      intentId,
      providerId,
      ptx,
      classification,
      expected,
      settled,
      currency,
      detail
    );
    db.exec(
      "INSERT INTO reconciliation_audit_log(id,exception_id,actor,previous_status,next_status,action,note) VALUES(?,?,?,'NONE','OPEN','DETECTED',?)",
      Json.id(),
      exId,
      "reconciliation-engine",
      detail
    );
  }

  @GetMapping("/reconciliation/runs")
  public List<Map<String, Object>> listRuns(Authentication auth) {
    return db.rows(
      "SELECT * FROM reconciliation_runs WHERE merchant_id=? ORDER BY created_at DESC LIMIT 50",
      Actor.of(auth).merchantId()
    );
  }

  @GetMapping("/reconciliation/runs/{id}/items")
  public List<Map<String, Object>> runItems(
    @PathVariable String id,
    Authentication auth
  ) {
    return db.rows(
      "SELECT * FROM reconciliation_items WHERE run_id=? AND merchant_id=? ORDER BY created_at",
      id,
      Actor.of(auth).merchantId()
    );
  }

  @GetMapping("/reconciliation/exceptions")
  public List<Map<String, Object>> listExceptions(Authentication auth) {
    return db.rows(
      "SELECT * FROM reconciliation_exceptions WHERE merchant_id=? ORDER BY created_at DESC LIMIT 100",
      Actor.of(auth).merchantId()
    );
  }

  @GetMapping("/reconciliation/exceptions/{id}/audit")
  public List<Map<String, Object>> exceptionAudit(
    @PathVariable String id,
    Authentication auth
  ) {
    db.one(
      "SELECT id FROM reconciliation_exceptions WHERE id=? AND merchant_id=?",
      id,
      Actor.of(auth).merchantId()
    );
    return db.rows(
      "SELECT * FROM reconciliation_audit_log WHERE exception_id=? ORDER BY created_at",
      id
    );
  }

  @PostMapping("/reconciliation/exceptions/{id}/actions")
  public Map<String, Object> actOnException(
    @PathVariable String id,
    @RequestBody Map<String, Object> body,
    Authentication auth
  ) {
    var actor = Actor.of(auth);
    actor.requireOperations();
    String nextStatus = Json.text(body, "status");
    String action = Json.text(body, "action");
    String note = Json.text(body, "note").trim();
    if (!Set.of("INVESTIGATING", "RESOLVED").contains(nextStatus)) {
      throw ApiError.bad("Status must be INVESTIGATING or RESOLVED");
    }
    if (action.isBlank() || note.isBlank()) {
      throw ApiError.bad("Provide an action and audit note");
    }
    return db.tx(() -> {
      var ex = db.one(
        "SELECT * FROM reconciliation_exceptions WHERE id=? AND merchant_id=? FOR UPDATE",
        id,
        actor.merchantId()
      );
      String prev = Json.text(ex, "status");
      db.exec(
        "UPDATE reconciliation_exceptions SET status=?,resolution_action=?,resolution_note=?,resolved_by=?,updated_at=now() WHERE id=?",
        nextStatus,
        action,
        note,
        actor.username(),
        id
      );
      db.exec(
        "INSERT INTO reconciliation_audit_log(id,exception_id,actor,previous_status,next_status,action,note) VALUES(?,?,?,?,?,?,?)",
        Json.id(),
        id,
        actor.username(),
        prev,
        nextStatus,
        action,
        note
      );
      return db.one("SELECT * FROM reconciliation_exceptions WHERE id=?", id);
    });
  }
}
