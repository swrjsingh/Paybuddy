package dev.paybuddy.ledgerreconciliation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
  scanBasePackages = {
    "dev.paybuddy.common",
    "dev.paybuddy.ledgerreconciliation",
  }
)
@EnableScheduling
public class LedgerReconciliationApplication {

  public static void main(String[] args) {
    SpringApplication.run(LedgerReconciliationApplication.class, args);
  }
}
