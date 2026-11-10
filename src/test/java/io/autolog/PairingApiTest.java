package io.autolog;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import io.autolog.auth.TokenCodec;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PairingApiTest extends ApiTestSupport {
  @Test
  void issuesEightDigitsAndPreservesLeadingZerosAndLongAccessTokens() throws Exception {
    try (var tokens = mockStatic(TokenCodec.class, CALLS_REAL_METHODS)) {
      tokens.when(TokenCodec::createPairingCode).thenReturn("00123456");
      var before = Instant.now();
      var issued = call("POST", "/api/vehicles/" + vehicle + "/pairing", owner, null, 200);
      String code = issued.path("token").asText();
      assertThat(code).isEqualTo("00123456");
      assertThat(
              Duration.between(before, Instant.parse(issued.path("expiresAt").asText()))
                  .toSeconds())
          .isBetween(599L, 602L);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM credentials WHERE kind='PAIRING' AND token_hash=?",
                  Integer.class,
                  TokenCodec.hash(code)))
          .isEqualTo(1);
      call("GET", "/api/auth/me", code, null, 401);
      var paired = call("POST", "/api/device/pair", null, Map.of("pairingToken", code), 200);
      assertThat(paired.path("accessToken").asText()).hasSize(43);
      assertThat(owner).hasSize(43);
      call("POST", "/api/device/pair", null, Map.of("pairingToken", code), 401);
    }
  }

  @Test
  void codeCollisionRetriesWithoutAbortingTransactionAndReissueRevokesPrevious() throws Exception {
    try (var tokens = mockStatic(TokenCodec.class, CALLS_REAL_METHODS)) {
      tokens.when(TokenCodec::createPairingCode).thenReturn("12345678", "12345678", "87654321");
      var first = call("POST", "/api/vehicles/" + vehicle + "/pairing", owner, null, 200);
      var second = call("POST", "/api/vehicles/" + vehicle + "/pairing", owner, null, 200);
      assertThat(first.path("token").asText()).isEqualTo("12345678");
      assertThat(second.path("token").asText()).isEqualTo("87654321");
      call(
          "POST",
          "/api/device/pair",
          null,
          Map.of("pairingToken", first.path("token").asText()),
          401);
      call(
          "POST",
          "/api/device/pair",
          null,
          Map.of("pairingToken", second.path("token").asText()),
          200);
    }
  }

  @Test
  void expiredAndMalformedCodesCannotPair() throws Exception {
    var issued = call("POST", "/api/vehicles/" + vehicle + "/pairing", owner, null, 200);
    var code = issued.path("token").asText();
    assertThat(code).matches("[0-9]{8}");
    jdbc.update(
        "UPDATE credentials SET expires_at=now()-interval '1 second' WHERE token_hash=?",
        TokenCodec.hash(code));
    call("POST", "/api/device/pair", null, Map.of("pairingToken", code), 401);
    for (String invalid :
        new String[] {
          "", "1234567", "123456789", "abcdefgh", "１２３４５６７８", "12 34567", TokenCodec.create()
        }) call("POST", "/api/device/pair", null, Map.of("pairingToken", invalid), 400);
  }

  @Test
  void guessingIsThrottledBeforeAValidCodeIsConsumed() throws Exception {
    var issued = call("POST", "/api/vehicles/" + vehicle + "/pairing", owner, null, 200);
    for (int i = 0; i < 20; i++) {
      var response =
          mvc.perform(
                  post("/api/device/pair")
                      .with(
                          r -> {
                            r.setRemoteAddr("192.0.2.99");
                            return r;
                          })
                      .contentType("application/json")
                      .content("{\"pairingToken\":\"invalid\"}"))
              .andReturn()
              .getResponse();
      assertThat(response.getStatus()).isEqualTo(400);
    }
    var blocked =
        mvc.perform(
                post("/api/device/pair")
                    .with(
                        r -> {
                          r.setRemoteAddr("192.0.2.99");
                          return r;
                        })
                    .contentType("application/json")
                    .content(
                        json.writeValueAsBytes(
                            Map.of("pairingToken", issued.path("token").asText()))))
            .andReturn()
            .getResponse();
    assertThat(blocked.getStatus()).isEqualTo(429);
    assertThat(blocked.getHeader("Retry-After")).isNotBlank();
    call(
        "POST",
        "/api/device/pair",
        null,
        Map.of("pairingToken", issued.path("token").asText()),
        200);
  }
}
