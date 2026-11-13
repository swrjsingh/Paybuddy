package dev.paybuddy.gatewayidentity;

import dev.paybuddy.common.*;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class IdentityController {

  private final Db db;
  private final BCryptPasswordEncoder passwords;
  private final JwtEncoder encoder;

  public IdentityController(
    Db db,
    BCryptPasswordEncoder passwords,
    JwtEncoder encoder
  ) {
    this.db = db;
    this.passwords = passwords;
    this.encoder = encoder;
  }

  @Bean
  ApplicationRunner seed() {
    return args -> {
      seedUser(
        "merchant@paybuddy.local",
        "PayBuddy123!",
        "demo-merchant",
        "MERCHANT",
        "Aarav's Store"
      );
      seedUser(
        "merchant2@paybuddy.local",
        "PayBuddy123!",
        "second-merchant",
        "MERCHANT",
        "Second Store"
      );
      seedUser(
        "ops@paybuddy.local",
        "OpsBuddy123!",
        "demo-merchant",
        "OPERATIONS",
        "PayBuddy Operations"
      );
    };
  }

  private void seedUser(
    String email,
    String password,
    String merchant,
    String role,
    String name
  ) {
    db.exec(
      "INSERT INTO users VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",
      email,
      passwords.encode(password),
      merchant,
      role,
      name
    );
  }

  @PostMapping("/auth/login")
  public Map<String, Object> login(@RequestBody Map<String, Object> body) {
    String email = Json.text(body, "email").trim().toLowerCase(Locale.ROOT);
    var rows = db.rows("SELECT * FROM users WHERE email=?", email);
    if (
      rows.isEmpty() ||
      !passwords.matches(
        Json.text(body, "password"),
        Json.text(rows.getFirst(), "passwordHash")
      )
    ) throw new ApiError(
      org.springframework.http.HttpStatus.UNAUTHORIZED,
      "Incorrect email or password"
    );
    var u = rows.getFirst();
    Instant now = Instant.now();
    var claims = JwtClaimsSet.builder()
      .issuer("paybuddy")
      .subject(email)
      .issuedAt(now)
      .expiresAt(now.plusSeconds(7200))
      .claim("merchantId", u.get("merchantId"))
      .claim("roles", List.of(u.get("role")))
      .build();
    String token = encoder
      .encode(
        JwtEncoderParameters.from(
          JwsHeader.with(MacAlgorithm.HS256).build(),
          claims
        )
      )
      .getTokenValue();
    return Map.of(
      "token",
      token,
      "email",
      email,
      "merchantId",
      u.get("merchantId"),
      "role",
      u.get("role"),
      "displayName",
      u.get("displayName"),
      "expiresAt",
      now.plusSeconds(7200).toString()
    );
  }

  @GetMapping("/auth/me")
  public Map<String, Object> me(Authentication authentication) {
    var a = Actor.of(authentication);
    return db.one(
      "SELECT email,merchant_id,role,display_name FROM users WHERE email=?",
      a.username()
    );
  }
}
