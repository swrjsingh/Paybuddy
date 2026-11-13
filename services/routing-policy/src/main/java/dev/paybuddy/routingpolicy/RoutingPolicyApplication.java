package dev.paybuddy.routingpolicy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
  scanBasePackages = { "dev.paybuddy.common", "dev.paybuddy.routingpolicy" }
)
@EnableScheduling
public class RoutingPolicyApplication {

  public static void main(String[] args) {
    SpringApplication.run(RoutingPolicyApplication.class, args);
  }
}
