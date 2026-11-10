package io.autolog.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.autolog.common.ApiErrorHandler;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/** Bounds guesses against short pairing codes for the current single-server setup. */
public final class PairingRateLimitFilter extends OncePerRequestFilter {
  private static final Duration WINDOW = Duration.ofMinutes(10);
  private static final int MAX_ATTEMPTS = 20;
  private static final int MAX_CLIENTS = 10_000;
  private final Map<String, AttemptWindow> clients = new HashMap<>();
  private final ObjectMapper json;
  private final Clock clock;

  public PairingRateLimitFilter(ObjectMapper json) {
    this(json, Clock.systemUTC());
  }

  PairingRateLimitFilter(ObjectMapper json, Clock clock) {
    this.json = json;
    this.clock = clock;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getMethod().equals("POST")
        || !UrlPathHelper.defaultInstance
            .getPathWithinApplication(request)
            .equals("/api/device/pair");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    long retryAfter = retryAfter(request.getRemoteAddr(), clock.instant());
    if (retryAfter == 0) {
      chain.doFilter(request, response);
      return;
    }
    response.setStatus(429);
    response.setHeader("Retry-After", Long.toString(retryAfter));
    response.setContentType("application/json;charset=UTF-8");
    json.writeValue(
        response.getOutputStream(),
        ApiErrorHandler.body("RATE_LIMITED", "페어링 시도가 너무 많습니다. 잠시 후 다시 시도하세요."));
  }

  private synchronized long retryAfter(String address, Instant now) {
    clients.values().removeIf(window -> !now.isBefore(window.expiresAt()));
    var window = clients.get(address);
    if (window == null) {
      if (clients.size() >= MAX_CLIENTS) return WINDOW.toSeconds();
      clients.put(address, new AttemptWindow(now.plus(WINDOW), 1));
      return 0;
    }
    if (window.attempts() >= MAX_ATTEMPTS)
      return Math.max(1, (Duration.between(now, window.expiresAt()).toMillis() + 999) / 1000);
    clients.put(address, new AttemptWindow(window.expiresAt(), window.attempts() + 1));
    return 0;
  }

  private record AttemptWindow(Instant expiresAt, int attempts) {}
}
