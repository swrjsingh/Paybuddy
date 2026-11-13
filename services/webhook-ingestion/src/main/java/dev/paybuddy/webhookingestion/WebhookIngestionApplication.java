package dev.paybuddy.webhookingestion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
  scanBasePackages = { "dev.paybuddy.common", "dev.paybuddy.webhookingestion" }
)
@EnableScheduling
public class WebhookIngestionApplication {

  public static void main(String[] args) {
    SpringApplication.run(WebhookIngestionApplication.class, args);
  }
}
