package dev.paybuddy.provideradapter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
  scanBasePackages = { "dev.paybuddy.common", "dev.paybuddy.provideradapter" }
)
@EnableScheduling
public class ProviderAdapterApplication {

  public static void main(String[] args) {
    SpringApplication.run(ProviderAdapterApplication.class, args);
  }
}
