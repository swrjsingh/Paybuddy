package dev.paybuddy.common;

import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class Db {

  public final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  public Db(JdbcTemplate jdbc, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(manager);
  }

  public <T> T tx(Supplier<T> action) {
    return tx.execute(status -> action.get());
  }

  public int exec(String sql, Object... args) {
    return jdbc.update(sql, args);
  }

  public List<Map<String, Object>> rows(String sql, Object... args) {
    return jdbc.query(
      sql,
      (rs, n) -> {
        Map<String, Object> m = new LinkedHashMap<>();
        var meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
          String k = meta.getColumnLabel(i);
          StringBuilder c = new StringBuilder();
          boolean upper = false;
          for (char ch : k.toCharArray()) {
            if (ch == '_') {
              upper = true;
            } else {
              c.append(upper ? Character.toUpperCase(ch) : ch);
              upper = false;
            }
          }
          Object v = rs.getObject(i);
          if (v instanceof java.sql.Timestamp t) v = t.toInstant().toString();
          m.put(c.toString(), v);
        }
        return m;
      },
      args
    );
  }

  public Map<String, Object> one(String sql, Object... args) {
    var r = rows(sql, args);
    if (r.isEmpty()) throw ApiError.notFound();
    return r.getFirst();
  }

  public void event(String id, String type, Map<String, Object> event) {
    exec(
      "INSERT INTO outbox(id,event_type,payload) VALUES(?,?,?) ON CONFLICT(id) DO NOTHING",
      id,
      type,
      Json.write(event)
    );
  }

  public boolean first(String id) {
    return (
      exec(
        "INSERT INTO processed_events(id) VALUES(?) ON CONFLICT DO NOTHING",
        id
      ) == 1
    );
  }
}
