package dev.paybuddy.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class Json {

  private static final ObjectMapper M = new ObjectMapper();

  public static String write(Object value) {
    try {
      return M.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JSON", e);
    }
  }

  public static Map<String, Object> read(String value) {
    try {
      return M.readValue(value, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JSON", e);
    }
  }

  public static String text(Map<String, Object> m, String key) {
    Object v = m.get(key);
    return v == null ? "" : v.toString();
  }

  public static long number(Map<String, Object> m, String key) {
    Object v = m.get(key);
    try {
      if (v instanceof Number n) {
        if (
          n.doubleValue() != n.longValue()
        ) throw new IllegalArgumentException();
        return n.longValue();
      }
      return Long.parseLong(String.valueOf(v));
    } catch (Exception e) {
      throw new IllegalArgumentException(key + " must be an integer");
    }
  }

  public static String id() {
    return UUID.randomUUID().toString();
  }

  public static String hash(String s) {
    try {
      return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(
          s.getBytes(StandardCharsets.UTF_8)
        )
      );
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static String sign(String secret, String timestamp, String body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(
        new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")
      );
      return HexFormat.of().formatHex(
        mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8))
      );
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static boolean same(String a, String b) {
    return MessageDigest.isEqual(
      a.getBytes(StandardCharsets.UTF_8),
      b.getBytes(StandardCharsets.UTF_8)
    );
  }
}
