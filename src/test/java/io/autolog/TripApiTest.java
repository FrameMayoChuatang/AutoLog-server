package io.autolog;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class TripApiTest extends ApiTestSupport {
  private static final String START = "2026-10-10T00:00:00Z", END = "2026-10-10T01:00:00Z";

  @Test
  void parallelApprovalsCannotDoubleCountOverlappingTrips() throws Exception {
    String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
    completed(first, START, END, 100, 110);
    completed(second, START, END, 100, 110);
    call("POST", "/api/device/trips/" + first + "/confirm", device, details(1, "BUSINESS"), 200);
    call("POST", "/api/device/trips/" + second + "/confirm", device, details(1, "BUSINESS"), 200);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      List<Future<Integer>> tasks = new ArrayList<>();
      for (String id : List.of(first, second))
        tasks.add(
            executor.submit(
                () -> {
                  gate.await();
                  return response(
                          "POST",
                          "/api/trips/" + id + "/approve",
                          owner,
                          Map.of("expectedVersion", 2))
                      .getResponse()
                      .getStatus();
                }));
      gate.countDown();
      List<Integer> statuses = new ArrayList<>();
      for (var task : tasks) statuses.add(task.get(15, TimeUnit.SECONDS));
      assertThat(statuses).containsExactlyInAnyOrder(200, 409);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM trips WHERE approval_status='APPROVED'", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void offlineRetryIsIdempotentAndStaleRevisionCannotOverwrite() throws Exception {
    String id = UUID.randomUUID().toString();
    var body = syncBody(1, 0, "RUNNING", START, null, 100, null);
    call("PUT", "/api/device/trips/" + id, device, body, 200);
    var replay = call("PUT", "/api/device/trips/" + id, device, body, 200);
    assertThat(replay.path("replayed").asBoolean()).isTrue();
    assertThat(replay.path("serverVersion").asInt()).isEqualTo(1);
    call(
        "PUT",
        "/api/device/trips/" + id,
        device,
        syncBody(1, 0, "COMPLETED", START, END, 100, 110),
        409);
    call(
        "PUT",
        "/api/device/trips/" + id,
        device,
        syncBody(2, 0, "COMPLETED", START, END, 100, 110),
        409);
    var done =
        call(
            "PUT",
            "/api/device/trips/" + id,
            device,
            syncBody(2, 1, "COMPLETED", START, END, 100, 110),
            200);
    assertThat(done.path("distanceKm").decimalValue()).isEqualByComparingTo("10");
    call("PUT", "/api/device/trips/" + id, device, body, 409);
    assertThat(call("GET", "/api/trips/" + id + "/audit", owner, null, 200)).hasSize(2);
  }

  @Test
  void parallelDuplicateUploadsCreateOneTripAndOneAuditEvent() throws Exception {
    String id = UUID.randomUUID().toString();
    var body = syncBody(1, 0, "COMPLETED", START, END, 100, 110);
    try (var executor = Executors.newFixedThreadPool(4)) {
      var gate = new CountDownLatch(1);
      List<Future<Integer>> tasks = new ArrayList<>();
      for (int i = 0; i < 4; i++)
        tasks.add(
            executor.submit(
                () -> {
                  gate.await();
                  return response("PUT", "/api/device/trips/" + id, device, body)
                      .getResponse()
                      .getStatus();
                }));
      gate.countDown();
      for (var task : tasks) assertThat(task.get(15, TimeUnit.SECONDS)).isEqualTo(200);
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM trips", Integer.class)).isEqualTo(1);
    assertThat(call("GET", "/api/trips/" + id + "/audit", owner, null, 200)).hasSize(1);
  }

  @Test
  void approvalRequiresConfirmationAndEditsInvalidateApproval() throws Exception {
    String id = UUID.randomUUID().toString();
    var upload = syncBody(1, 0, "COMPLETED", START, END, 100, 110);
    call("PUT", "/api/device/trips/" + id, device, upload, 200);
    call("POST", "/api/trips/" + id + "/approve", owner, Map.of("expectedVersion", 1), 409);
    call("POST", "/api/device/trips/" + id + "/confirm", device, details(1, "BUSINESS"), 200);
    var approved =
        call("POST", "/api/trips/" + id + "/approve", owner, Map.of("expectedVersion", 2), 200);
    assertThat(approved.path("reportEligible").asBoolean()).isTrue();
    var replay = call("PUT", "/api/device/trips/" + id, device, upload, 200);
    assertThat(replay.path("approvalStatus").asText()).isEqualTo("APPROVED");
    call(
        "PUT",
        "/api/device/trips/" + id,
        device,
        syncBody(2, 3, "COMPLETED", START, END, 100, 999),
        409);
    var edit = call("PATCH", "/api/trips/" + id, owner, details(3, "PERSONAL"), 200);
    assertThat(edit.path("approvalStatus").asText()).isEqualTo("PENDING");
    assertThat(edit.path("reportEligible").asBoolean()).isFalse();
    call("POST", "/api/trips/" + id + "/approve", owner, Map.of("expectedVersion", 3), 409);
    call("POST", "/api/trips/" + id + "/approve", owner, Map.of("expectedVersion", 4), 200);
    var audit = call("GET", "/api/trips/" + id + "/audit", owner, null, 200);
    assertThat(audit).hasSize(5);
    assertThat(audit.get(3).path("beforeValue").path("classification").asText())
        .isEqualTo("BUSINESS");
    assertThat(audit.get(3).path("afterValue").path("classification").asText())
        .isEqualTo("PERSONAL");
    assertThat(audit.get(3).path("reason").asText()).isEqualTo("테스트 수정 사유");
  }

  @Test
  void interruptedRecoveryPreservesUnknownDataAndRequiresFreshVersion() throws Exception {
    String id = UUID.randomUUID().toString();
    var body = syncBody(1, 0, "INTERRUPTED", START, null, 100, null);
    body.put("lastObservedAt", END);
    body.put("lastOdometerKm", 105);
    var result = call("PUT", "/api/device/trips/" + id, device, body, 200);
    assertThat(result.path("distanceKm").isNull()).isTrue();
    assertThat(result.path("observedDistanceKm").decimalValue()).isEqualByComparingTo("5");
    call("POST", "/api/device/trips/" + id + "/confirm", device, details(1, "BUSINESS"), 409);
    call(
        "PUT",
        "/api/device/trips/" + id,
        device,
        syncBody(2, 1, "COMPLETED", START, END, 100, 105),
        200);
    call("POST", "/api/device/trips/" + id + "/confirm", device, details(2, "BUSINESS"), 200);
  }

  @Test
  void missingRegressedAndBootZeroOdometersNeverBecomeApprovedDistance() throws Exception {
    for (Number[] readings : new Number[][] {{100, null}, {100, 90}, {0, 100}}) {
      String id = UUID.randomUUID().toString();
      var trip = completed(id, START, END, readings[0], readings[1]);
      assertThat(trip.path("distanceKm").isNull()).isTrue();
      assertThat(trip.path("qualityIssues")).isNotEmpty();
      call("POST", "/api/device/trips/" + id + "/confirm", device, details(1, "BUSINESS"), 409);
    }
    assertThat(call("GET", "/api/trips", owner, null, 200).path("total").asInt()).isEqualTo(3);
  }

  @Test
  void changesRequestClearsConfirmationUntilDriverReviewsAgain() throws Exception {
    String id = approved("BUSINESS", START, END, 100, 110);
    call(
        "POST",
        "/api/trips/" + id + "/request-changes",
        owner,
        Map.of("expectedVersion", 3, "reason", "목적 재확인"),
        200);
    var row = call("GET", "/api/device/trips/" + id, device, null, 200);
    assertThat(row.path("driverConfirmedAt").isNull()).isTrue();
    call("POST", "/api/trips/" + id + "/approve", owner, Map.of("expectedVersion", 4), 409);
    call("POST", "/api/device/trips/" + id + "/confirm", device, details(4, "COMMUTE"), 200);
    call("POST", "/api/trips/" + id + "/approve", owner, Map.of("expectedVersion", 5), 200);
  }

  @Test
  void tenantAndVehicleScopesCoverEveryTripOperation() throws Exception {
    String id = approved("BUSINESS", START, END, 100, 110);
    String other = signup("other@example.test").path("accessToken").asText();
    call("GET", "/api/trips/" + id, other, null, 404);
    call("GET", "/api/trips/" + id + "/audit", other, null, 404);
    call("PATCH", "/api/trips/" + id, other, details(3, "PERSONAL"), 404);
    call("POST", "/api/trips/" + id + "/approve", other, Map.of("expectedVersion", 3), 404);
    assertThat(call("GET", "/api/trips", other, null, 200).path("items")).isEmpty();
    String v2 =
        call(
                "POST",
                "/api/vehicles",
                owner,
                Map.of("registrationNumber", "22가2222", "model", "두번째", "active", true),
                201)
            .path("id")
            .asText();
    String pair =
        call("POST", "/api/vehicles/" + v2 + "/pairing", owner, null, 200).path("token").asText();
    String d2 =
        call("POST", "/api/device/pair", null, Map.of("pairingToken", pair), 200)
            .path("accessToken")
            .asText();
    call("GET", "/api/device/trips/" + id, d2, null, 404);
    call("POST", "/api/device/trips/" + id + "/confirm", d2, details(3, "PERSONAL"), 404);
    call(
        "PUT",
        "/api/device/trips/" + id,
        d2,
        syncBody(1, 0, "COMPLETED", START, END, 100, 110),
        409);
    call("GET", "/api/device/trips?vehicleId=" + vehicle, d2, null, 404);
  }

  @Test
  void foreignDriverAndPurposeAreRejectedAndPendingDriverCannotConfirm() throws Exception {
    String other = signup("other@example.test").path("accessToken").asText();
    String foreignDriver =
        call("POST", "/api/drivers", other, Map.of("name", "외부", "department", "타사"), 201)
            .path("id")
            .asText();
    String id = UUID.randomUUID().toString();
    var body = syncBody(1, 0, "COMPLETED", START, END, 100, 110);
    body.put("driverId", foreignDriver);
    call("PUT", "/api/device/trips/" + id, device, body, 404);
    completed(id, START, END, 100, 110);
    var details = details(1, "BUSINESS");
    details.put("purposeId", UUID.randomUUID().toString());
    call("POST", "/api/device/trips/" + id + "/confirm", device, details, 404);
    String pending =
        call("POST", "/api/device/drivers", device, Map.of("name", "대기", "department", "영업"), 201)
            .path("id")
            .asText();
    String id2 = UUID.randomUUID().toString();
    body.put("driverId", pending);
    call("PUT", "/api/device/trips/" + id2, device, body, 200);
    details = details(1, "BUSINESS");
    details.put("driverId", pending);
    call("POST", "/api/device/trips/" + id2 + "/confirm", device, details, 409);
    call("POST", "/api/drivers/" + pending + "/approve", owner, null, 200);
    call("POST", "/api/device/trips/" + id2 + "/confirm", device, details, 200);
  }

  @Test
  void historySurvivesCatalogChangesAndRecommendationsExcludeInactiveEntries() throws Exception {
    String id = approved("BUSINESS", START, END, 100, 110);
    assertThat(call("GET", "/api/device/drivers/" + driver + "/recommendations", device, null, 200))
        .hasSize(1);
    assertThat(
            call("GET", "/api/device/drivers/recent", device, null, 200).get(0).path("id").asText())
        .isEqualTo(driver);
    call(
        "PATCH",
        "/api/catalog/purposes/" + purpose,
        owner,
        Map.of("name", "변경된 목적", "active", false),
        200);
    assertThat(call("GET", "/api/device/drivers/" + driver + "/recommendations", device, null, 200))
        .isEmpty();
    var old = call("PATCH", "/api/trips/" + id, owner, details(3, "BUSINESS"), 200);
    assertThat(old.path("purposeText").asText()).isEqualTo("고객 미팅");
    String newId = UUID.randomUUID().toString();
    completed(newId, START, END, 110, 120);
    call("POST", "/api/device/trips/" + newId + "/confirm", device, details(1, "BUSINESS"), 409);
  }

  @Test
  void gapsUsePreviousMonthBoundaryAndNeverFillInUnknownSegments() throws Exception {
    completed(
        UUID.randomUUID().toString(), "2026-09-30T01:00:00Z", "2026-09-30T02:00:00Z", 100, 110);
    completed(UUID.randomUUID().toString(), START, END, 120, 130);
    var gaps = call("GET", "/api/trips/gaps?month=2026-10&vehicleId=" + vehicle, owner, null, 200);
    assertThat(gaps.path("knownGapKm").decimalValue()).isEqualByComparingTo("10");
    assertThat(gaps.path("unknownBoundaryCount").asInt()).isZero();
    String interrupted = UUID.randomUUID().toString();
    call(
        "PUT",
        "/api/device/trips/" + interrupted,
        device,
        syncBody(1, 0, "INTERRUPTED", "2026-10-11T00:00:00Z", null, 130, null),
        200);
    completed(
        UUID.randomUUID().toString(), "2026-10-12T00:00:00Z", "2026-10-12T01:00:00Z", 140, 150);
    gaps = call("GET", "/api/trips/gaps?month=2026-10", owner, null, 200);
    assertThat(gaps.path("knownGapKm").decimalValue()).isEqualByComparingTo("10");
    assertThat(gaps.path("unknownBoundaryCount").asInt()).isEqualTo(1);
  }

  @Test
  void queryFiltersAndValidationAreConsistent() throws Exception {
    approved("PERSONAL", START, END, 100, 110);
    completed(UUID.randomUUID().toString(), START, END, 110, 120);
    assertThat(
            call(
                    "GET",
                    "/api/trips?approval=APPROVED&classification=PERSONAL&size=1",
                    owner,
                    null,
                    200)
                .path("total")
                .asInt())
        .isEqualTo(1);
    call("GET", "/api/trips?size=0", owner, null, 400);
    call("GET", "/api/trips?from=2026-10-11T00:00:00Z&to=" + START, owner, null, 400);
    String id = UUID.randomUUID().toString();
    var invalid = syncBody(1, 0, "COMPLETED", END, START, 100, 110);
    call("PUT", "/api/device/trips/" + id, device, invalid, 400);
    invalid = syncBody(1, 0, "RUNNING", START, END, 100, 110);
    call("PUT", "/api/device/trips/" + id, device, invalid, 400);
  }
}
