package dev.paybuddy.paymentintent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
  scanBasePackages = { "dev.paybuddy.common", "dev.paybuddy.paymentintent" }
)
@EnableScheduling
public class PaymentIntentApplication {

  public static void main(String[] args) {
    SpringApplication.run(PaymentIntentApplication.class, args);
  }
}
