package dev.paybuddy.common;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class InternalHttp {

  private final RestClient client;

  public InternalHttp(@Value("${paybuddy.internal-secret}") String key) {
    var f = new SimpleClientHttpRequestFactory();
    f.setConnectTimeout(2000);
    f.setReadTimeout(3000);
    client = RestClient.builder()
      .requestFactory(f)
      .defaultHeader("X-Service-Key", key)
      .build();
  }

  public Map<String, Object> get(String url) {
    return client
      .get()
      .uri(url)
      .retrieve()
      .body(new ParameterizedTypeReference<Map<String, Object>>() {});
  }

  public Map<String, Object> post(String url, Map<String, Object> body) {
    return client
      .post()
      .uri(url)
      .body(body)
      .retrieve()
      .body(new ParameterizedTypeReference<Map<String, Object>>() {});
  }
}
