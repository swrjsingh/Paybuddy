package dev.paybuddy.gatewayidentity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
  scanBasePackages = { "dev.paybuddy.common", "dev.paybuddy.gatewayidentity" }
)
@EnableScheduling
public class GatewayIdentityApplication {

  public static void main(String[] args) {
    SpringApplication.run(GatewayIdentityApplication.class, args);
  }
}
