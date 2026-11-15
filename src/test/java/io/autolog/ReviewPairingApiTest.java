package io.autolog;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.autolog.auth.TokenCodec;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    properties = {
      "autolog.review-pairing.enabled=true",
      "autolog.review-pairing.code=00123456",
      "autolog.review-pairing.company-id=7feec840-e7d0-49dc-a3d1-dbf640d10001",
      "autolog.review-pairing.vehicle-id=7feec840-e7d0-49dc-a3d1-dbf640d10002"
    })
class ReviewPairingApiTest extends ApiTestSupport {
  private static final String REVIEW_CODE = "00123456";
  private static final UUID REVIEW_COMPANY =
      UUID.fromString("7feec840-e7d0-49dc-a3d1-dbf640d10001");
  private static final UUID REVIEW_VEHICLE =
      UUID.fromString("7feec840-e7d0-49dc-a3d1-dbf640d10002");

  @BeforeEach
  void reviewVehicle() {
    jdbc.update("INSERT INTO companies(id,name) VALUES(?,?)", REVIEW_COMPANY, "심사용 테스트 회사");
    jdbc.update(
        "INSERT INTO vehicles(id,company_id,registration_number,model) VALUES(?,?,?,?)",
        REVIEW_VEHICLE,
        REVIEW_COMPANY,
        "TEST-001",
        "심사용 차량");
  }

  @Test
  void reusableCodeIssuesIndependentExpiringTokensForOnlyTheConfiguredVehicle() throws Exception {
    String otherTrip = UUID.randomUUID().toString();
    completed(otherTrip, "2026-11-01T00:00:00Z", "2026-11-01T01:00:00Z", 100, 110);
    var before = Instant.now();
    var first = pair();
    var second = pair();
    String firstToken = first.path("accessToken").asText();
    String secondToken = second.path("accessToken").asText();
    assertThat(firstToken).hasSize(43).isNotEqualTo(secondToken);
    assertThat(second.path("vehicle").path("id").asText()).isEqualTo(REVIEW_VEHICLE.toString());
    assertThat(second.path("vehicle").path("companyId").asText())
        .isEqualTo(REVIEW_COMPANY.toString());
    assertThat(
            Duration.between(before, Instant.parse(second.path("expiresAt").asText())).toSeconds())
        .isBetween(Duration.ofDays(90).toSeconds() - 1, Duration.ofDays(90).toSeconds() + 3);
    for (String token : new String[] {firstToken, secondToken}) {
      call("GET", "/api/device/vehicle", token, null, 200);
      call("GET", "/api/vehicles", token, null, 403);
      call("GET", "/api/device/trips/" + otherTrip, token, null, 404);
    }
    call("GET", "/api/auth/me", REVIEW_CODE, null, 401);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM credentials WHERE token_hash=?",
                Integer.class,
                TokenCodec.hash(REVIEW_CODE)))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM credentials WHERE company_id=? AND vehicle_id=? AND kind='DEVICE' AND revoked_at IS NULL",
                Integer.class,
                REVIEW_COMPANY,
                REVIEW_VEHICLE))
        .isEqualTo(2);
  }

  @Test
  void concurrentReviewersStayConnected() throws Exception {
    try (var executor = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      List<Future<JsonNode>> requests = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        requests.add(
            executor.submit(
                () -> {
                  gate.await();
                  return pair();
                }));
      }
      gate.countDown();
      for (var request : requests) {
        String token = request.get(15, TimeUnit.SECONDS).path("accessToken").asText();
        call("GET", "/api/device/vehicle", token, null, 200);
      }
    }
  }

  @Test
  void inactiveVehicleRejectsNewPairingsAndExistingTokens() throws Exception {
    String token = pair().path("accessToken").asText();
    jdbc.update("UPDATE vehicles SET active=false WHERE id=?", REVIEW_VEHICLE);
    call("POST", "/api/device/pair", null, Map.of("pairingToken", REVIEW_CODE), 409);
    call("GET", "/api/device/vehicle", token, null, 401);
  }

  @Test
  void missingOrForeignVehicleNeverFallsBackToAnotherCompany() throws Exception {
    jdbc.update(
        "UPDATE vehicles SET company_id=? WHERE id=?", UUID.fromString(company), REVIEW_VEHICLE);
    call("POST", "/api/device/pair", null, Map.of("pairingToken", REVIEW_CODE), 404);
    jdbc.update("DELETE FROM vehicles WHERE id=?", REVIEW_VEHICLE);
    call("POST", "/api/device/pair", null, Map.of("pairingToken", REVIEW_CODE), 404);
  }

  @Test
  void existingCodeCollisionDoesNotConnectToEitherVehicle() throws Exception {
    jdbc.update(
        "INSERT INTO credentials(id,company_id,vehicle_id,token_hash,kind,expires_at) VALUES(?,?,?,?,'PAIRING',now()+interval '10 minutes')",
        UUID.randomUUID(),
        UUID.fromString(company),
        UUID.fromString(vehicle),
        TokenCodec.hash(REVIEW_CODE));
    call("POST", "/api/device/pair", null, Map.of("pairingToken", REVIEW_CODE), 409);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM credentials WHERE company_id=? AND kind='DEVICE'",
                Integer.class,
                REVIEW_COMPANY))
        .isZero();
    call("GET", "/api/device/vehicle", device, null, 200);
  }

  @Test
  void normalCodeIssuanceSkipsReviewCodeAndRemainsSingleUse() throws Exception {
    try (var tokens = mockStatic(TokenCodec.class, CALLS_REAL_METHODS)) {
      tokens.when(TokenCodec::createPairingCode).thenReturn(REVIEW_CODE, "88776655");
      var issued = call("POST", "/api/vehicles/" + vehicle + "/pairing", owner, null, 200);
      assertThat(issued.path("token").asText()).isEqualTo("88776655");
      call("POST", "/api/device/pair", null, Map.of("pairingToken", "88776655"), 200);
      call("POST", "/api/device/pair", null, Map.of("pairingToken", "88776655"), 401);
      call("GET", "/api/device/vehicle", device, null, 401);
      pair();
      pair();
    }
  }

  @Test
  void reviewCodeStillUsesPairingRateLimit() throws Exception {
    String firstToken = null;
    for (int i = 0; i < 21; i++) {
      var result =
          mvc.perform(
                  post("/api/device/pair")
                      .with(
                          request -> {
                            request.setRemoteAddr("192.0.2.155");
                            return request;
                          })
                      .contentType("application/json")
                      .content(json.writeValueAsBytes(Map.of("pairingToken", REVIEW_CODE))))
              .andReturn()
              .getResponse();
      assertThat(result.getStatus()).isEqualTo(i < 20 ? 200 : 429);
      if (i == 0)
        firstToken = json.readTree(result.getContentAsByteArray()).path("accessToken").asText();
      if (i == 20) assertThat(result.getHeader("Retry-After")).isNotBlank();
    }
    call("GET", "/api/device/vehicle", firstToken, null, 200);
  }

  private JsonNode pair() throws Exception {
    return call("POST", "/api/device/pair", null, Map.of("pairingToken", REVIEW_CODE), 200);
  }
}
