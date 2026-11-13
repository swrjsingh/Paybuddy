package dev.paybuddy.common;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

public record Actor(String merchantId, String username, boolean operations) {
  public static Actor of(Authentication a) {
    if (!(a instanceof JwtAuthenticationToken j)) throw ApiError.forbidden();
    return new Actor(
      j.getToken().getClaimAsString("merchantId"),
      j.getName(),
      a
        .getAuthorities()
        .stream()
        .anyMatch(x -> x.getAuthority().equals("ROLE_OPERATIONS"))
    );
  }

  public void requireOperations() {
    if (!operations) throw ApiError.forbidden();
  }
}
