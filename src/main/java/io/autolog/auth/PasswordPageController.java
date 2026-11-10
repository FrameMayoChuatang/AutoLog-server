package io.autolog.auth;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class PasswordPageController {
  @GetMapping("/password/setup")
  public ResponseEntity<ClassPathResource> page() {
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
        .cacheControl(CacheControl.noStore())
        .header("Referrer-Policy", "no-referrer")
        .header(
            "Content-Security-Policy",
            "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; form-action 'none'; frame-ancestors 'none'; base-uri 'none'")
        .body(new ClassPathResource("static/password/setup.html"));
  }
}
