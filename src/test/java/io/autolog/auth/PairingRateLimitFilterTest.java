package io.autolog.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PairingRateLimitFilterTest {
  @Test
  void limitsAttemptsByRemoteAddressAndResetsAfterWindow() throws Exception {
    var clock = mock(Clock.class);
    var now = Instant.parse("2026-11-11T00:00:00Z");
    when(clock.instant()).thenReturn(now);
    var filter =
        new PairingRateLimitFilter(JsonMapper.builder().findAndAddModules().build(), clock);
    var chain = mock(FilterChain.class);
    for (int i = 0; i < 20; i++)
      filter.doFilter(request("192.0.2.1"), new MockHttpServletResponse(), chain);
    verify(chain, times(20)).doFilter(any(), any());

    var blocked = new MockHttpServletResponse();
    var spoofed = request("192.0.2.1");
    spoofed.addHeader("X-Forwarded-For", "192.0.2.2");
    filter.doFilter(spoofed, blocked, chain);
    assertThat(blocked.getStatus()).isEqualTo(429);
    assertThat(blocked.getHeader("Retry-After")).isEqualTo("600");
    assertThat(blocked.getContentAsString()).contains("RATE_LIMITED");
    var encoded = request("192.0.2.1");
    encoded.setRequestURI("/api/device/%70air");
    var encodedResponse = new MockHttpServletResponse();
    filter.doFilter(encoded, encodedResponse, chain);
    assertThat(encodedResponse.getStatus()).isEqualTo(429);
    var underContext = request("192.0.2.1");
    underContext.setContextPath("/autolog");
    underContext.setRequestURI("/autolog/api/device/pair");
    var contextResponse = new MockHttpServletResponse();
    filter.doFilter(underContext, contextResponse, chain);
    assertThat(contextResponse.getStatus()).isEqualTo(429);
    verifyNoMoreInteractions(chain);

    filter.doFilter(request("192.0.2.2"), new MockHttpServletResponse(), chain);
    verify(chain, times(21)).doFilter(any(), any());
    when(clock.instant()).thenReturn(now.plusSeconds(600));
    filter.doFilter(request("192.0.2.1"), new MockHttpServletResponse(), chain);
    verify(chain, times(22)).doFilter(any(), any());
  }

  @Test
  void leavesOtherEndpointsAndPreflightUnchanged() throws Exception {
    var filter = new PairingRateLimitFilter(JsonMapper.builder().findAndAddModules().build());
    var chain = mock(FilterChain.class);
    for (int i = 0; i < 25; i++) {
      filter.doFilter(
          new MockHttpServletRequest("GET", "/api/device/drivers"),
          new MockHttpServletResponse(),
          chain);
      filter.doFilter(
          new MockHttpServletRequest("OPTIONS", "/api/device/pair"),
          new MockHttpServletResponse(),
          chain);
    }
    verify(chain, times(50)).doFilter(any(), any());
  }

  private MockHttpServletRequest request(String address) {
    var request = new MockHttpServletRequest("POST", "/api/device/pair");
    request.setRemoteAddr(address);
    return request;
  }
}
