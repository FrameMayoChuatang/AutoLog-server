package io.autolog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(
    exclude =
        org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
            .class)
public class AutoLogApplication {
  public static void main(String[] args) {
    SpringApplication.run(AutoLogApplication.class, args);
  }
}
