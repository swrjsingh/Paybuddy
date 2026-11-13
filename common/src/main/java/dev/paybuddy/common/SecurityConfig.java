package dev.paybuddy.common;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {

  @Bean
  BCryptPasswordEncoder passwords() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  JwtDecoder decoder(@Value("${paybuddy.jwt-secret}") String s) {
    return NimbusJwtDecoder.withSecretKey(
      new SecretKeySpec(
        s.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        "HmacSHA256"
      )
    )
      .macAlgorithm(MacAlgorithm.HS256)
      .build();
  }

  @Bean
  JwtEncoder encoder(@Value("${paybuddy.jwt-secret}") String s) {
    return new NimbusJwtEncoder(
      new ImmutableSecret<>(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    );
  }

  @Bean
  SecurityFilterChain chain(
    HttpSecurity http,
    @Value("${paybuddy.internal-secret}") String secret
  ) throws Exception {
    var converter = new JwtAuthenticationConverter();
    var grants = new JwtGrantedAuthoritiesConverter();
    grants.setAuthoritiesClaimName("roles");
    grants.setAuthorityPrefix("ROLE_");
    converter.setJwtGrantedAuthoritiesConverter(grants);
    var internal = new OncePerRequestFilter() {
      protected void doFilterInternal(
        HttpServletRequest r,
        HttpServletResponse response,
        FilterChain c
      ) throws ServletException, IOException {
        String supplied = r.getHeader("X-Service-Key");
        if (
          r.getRequestURI().startsWith("/internal/") &&
          supplied != null &&
          Json.same(secret, supplied)
        ) {
          SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
              "service",
              null,
              List.of(new SimpleGrantedAuthority("ROLE_SERVICE"))
            )
          );
        }
        c.doFilter(r, response);
      }
    };
    http
      .csrf(c -> c.disable())
      .sessionManagement(s ->
        s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
      )
      .addFilterBefore(internal, BearerTokenAuthenticationFilter.class)
      .authorizeHttpRequests(a ->
        a
          .requestMatchers(
            "/auth/login",
            "/actuator/health",
            "/webhooks/**",
            "/simulator/payments/*/view",
            "/simulator/payments/*/complete"
          )
          .permitAll()
          .requestMatchers("/internal/**")
          .hasAuthority("ROLE_SERVICE")
          .requestMatchers("/operations/**", "/routing/policies/**")
          .hasAuthority("ROLE_OPERATIONS")
          .anyRequest()
          .authenticated()
      )
      .oauth2ResourceServer(o ->
        o.jwt(j -> j.jwtAuthenticationConverter(converter))
      );
    return http.build();
  }
}
