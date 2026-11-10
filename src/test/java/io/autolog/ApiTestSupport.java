package io.autolog;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class ApiTestSupport {
  @Autowired protected MockMvc mvc;
  @Autowired protected ObjectMapper json;
  @Autowired protected JdbcTemplate jdbc;
  private static final AtomicInteger CLIENT_SEQUENCE = new AtomicInteger();
  private String clientAddress;
  protected String owner, company, vehicle, driver, device, purpose, client;
  protected static final String PASSWORD = "Local-test-password42!";

  @BeforeEach
  void reset() throws Exception {
    String database = jdbc.queryForObject("SELECT current_database()", String.class);
    if (database == null || !database.endsWith("_test"))
      throw new IllegalStateException("Tests require a dedicated *_test database");
    jdbc.execute("TRUNCATE companies CASCADE");
    clientAddress = "2001:db8::" + Integer.toHexString(CLIENT_SEQUENCE.incrementAndGet());
    var session = signup("owner@example.test");
    owner = session.path("accessToken").asText();
    company = session.path("admin").path("companyId").asText();
    vehicle =
        call(
                "POST",
                "/api/vehicles",
                owner,
                Map.of("registrationNumber", "12가3456", "model", "테스트 차량", "active", true),
                201)
            .path("id")
            .asText();
    driver =
        call("POST", "/api/drivers", owner, Map.of("name", "홍길동", "department", "개발팀"), 201)
            .path("id")
            .asText();
    purpose =
        call("POST", "/api/catalog/purposes", owner, Map.of("name", "고객 미팅", "active", true), 201)
            .path("id")
            .asText();
    client =
        call(
                "POST",
                "/api/catalog/clients",
                owner,
                Map.of("name", "가상 거래처", "address", "서울", "active", true),
                201)
            .path("id")
            .asText();
    String pair =
        call("POST", "/api/vehicles/" + vehicle + "/pairing", owner, null, 200)
            .path("token")
            .asText();
    device =
        call("POST", "/api/device/pair", null, Map.of("pairingToken", pair), 200)
            .path("accessToken")
            .asText();
  }

  protected JsonNode signup(String email) throws Exception {
    return call(
        "POST",
        "/api/auth/companies",
        null,
        Map.of("companyName", "가상 주식회사", "ownerName", "대표", "email", email, "password", PASSWORD),
        201);
  }

  protected MvcResult response(String method, String url, String token, Object body)
      throws Exception {
    var request =
        request(HttpMethod.valueOf(method), url)
            .with(
                r -> {
                  r.setRemoteAddr(clientAddress);
                  return r;
                });
    if (token != null) request.header("Authorization", "Bearer " + token);
    if (body != null) request.contentType("application/json").content(json.writeValueAsBytes(body));
    return mvc.perform(request).andReturn();
  }

  protected JsonNode call(String method, String url, String token, Object body, int status)
      throws Exception {
    var result = response(method, url, token, body);
    assertThat(result.getResponse().getStatus())
        .as(method + " " + url + ": " + result.getResponse().getContentAsString())
        .isEqualTo(status);
    byte[] bytes = result.getResponse().getContentAsByteArray();
    return bytes.length == 0 ? json.nullNode() : json.readTree(bytes);
  }

  protected Map<String, Object> syncBody(
      long revision,
      long base,
      String state,
      String start,
      String end,
      Number startKm,
      Number endKm) {
    var body = new LinkedHashMap<String, Object>();
    body.put("sourceRevision", revision);
    body.put("baseServerVersion", base);
    body.put("driverId", driver);
    body.put("startedAt", start);
    body.put("endedAt", end);
    body.put("startOdometerKm", startKm);
    body.put("endOdometerKm", endKm);
    body.put("state", state);
    return body;
  }

  protected Map<String, Object> details(long version, String classification) {
    var body = new LinkedHashMap<String, Object>();
    body.put("expectedVersion", version);
    body.put("driverId", driver);
    body.put("classification", classification);
    body.put("purposeId", purpose);
    body.put("clientId", client);
    body.put("reason", "테스트 수정 사유");
    return body;
  }

  protected JsonNode completed(String id, String start, String end, Number startKm, Number endKm)
      throws Exception {
    return call(
        "PUT",
        "/api/device/trips/" + id,
        device,
        syncBody(1, 0, "COMPLETED", start, end, startKm, endKm),
        200);
  }

  protected String approved(
      String classification, String start, String end, Number startKm, Number endKm)
      throws Exception {
    String id = UUID.randomUUID().toString();
    completed(id, start, end, startKm, endKm);
    call("POST", "/api/device/trips/" + id + "/confirm", device, details(1, classification), 200);
    call("POST", "/api/trips/" + id + "/approve", owner, Map.of("expectedVersion", 2), 200);
    return id;
  }
}
