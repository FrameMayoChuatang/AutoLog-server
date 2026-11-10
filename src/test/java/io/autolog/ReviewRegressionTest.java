package io.autolog;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ReviewRegressionTest extends ApiTestSupport {
  private static final String START = "2026-10-10T00:00:00Z", END = "2026-10-10T01:00:00Z";

  @Test
  void requestingChangesDoesNotUnlockConfirmedMeasurements() throws Exception {
    String id = approved("BUSINESS", START, END, 100, 110);
    call(
        "POST",
        "/api/trips/" + id + "/request-changes",
        owner,
        Map.of("expectedVersion", 3, "reason", "목적 재확인"),
        200);
    call(
        "PUT",
        "/api/device/trips/" + id,
        device,
        syncBody(2, 4, "COMPLETED", START, END, 100, 999),
        409);
    assertThat(call("GET", "/api/trips/" + id, owner, null, 200).path("endOdometerKm").asInt())
        .isEqualTo(110);
  }

  @Test
  void inactiveHistoricalDriverDoesNotPreventAdminPurposeCorrection() throws Exception {
    String id = approved("BUSINESS", START, END, 100, 110);
    call(
        "PATCH",
        "/api/drivers/" + driver,
        owner,
        Map.of("name", "새 이름", "department", "이전 부서", "status", "INACTIVE"),
        200);
    var edited = call("PATCH", "/api/trips/" + id, owner, details(3, "PERSONAL"), 200);
    assertThat(edited.path("driverName").asText()).isEqualTo("홍길동");
    assertThat(edited.path("approvalStatus").asText()).isEqualTo("PENDING");
  }

  @Test
  void inactiveDriverCanStillWithdrawLocationConsent() throws Exception {
    call(
        "POST",
        "/api/device/drivers/" + driver + "/consent",
        device,
        Map.of("granted", true, "noticeVersion", "v1"),
        200);
    call(
        "PATCH",
        "/api/drivers/" + driver,
        owner,
        Map.of("name", "홍길동", "department", "개발팀", "status", "INACTIVE"),
        200);
    assertThat(
            call(
                    "POST",
                    "/api/device/drivers/" + driver + "/consent",
                    device,
                    Map.of("granted", false, "noticeVersion", "v1"),
                    200)
                .path("locationConsent")
                .asBoolean())
        .isFalse();
    call(
        "POST",
        "/api/device/drivers/" + driver + "/consent",
        device,
        Map.of("granted", true, "noticeVersion", "v1"),
        409);
  }

  @Test
  void endReadingCannotIgnoreLargerLastObservedReading() throws Exception {
    String id = UUID.randomUUID().toString();
    var body = syncBody(1, 0, "COMPLETED", START, END, 100, 110);
    body.put("lastObservedAt", "2026-10-10T00:30:00Z");
    body.put("lastOdometerKm", 120);
    var result = call("PUT", "/api/device/trips/" + id, device, body, 200);
    assertThat(result.path("distanceKm").isNull()).isTrue();
    call("POST", "/api/device/trips/" + id + "/confirm", device, details(1, "BUSINESS"), 409);
  }

  @Test
  void nonAdjacentOverlapCannotBeCountedAsMissingDistance() throws Exception {
    completed(UUID.randomUUID().toString(), START, "2026-10-10T04:00:00Z", 100, 200);
    completed(UUID.randomUUID().toString(), END, "2026-10-10T01:30:00Z", 120, 130);
    completed(
        UUID.randomUUID().toString(), "2026-10-10T02:00:00Z", "2026-10-10T03:00:00Z", 140, 160);
    var gaps = call("GET", "/api/trips/gaps?month=2026-10", owner, null, 200);
    assertThat(gaps.path("knownGapKm").decimalValue()).isEqualByComparingTo("0");
    assertThat(gaps.path("items").get(2).path("issue").asText()).isEqualTo("OVERLAPPING_TRIPS");
  }

  @Test
  void confirmedFilterDoesNotConfuseClassificationAndApproval() throws Exception {
    String first = UUID.randomUUID().toString();
    completed(first, START, END, 100, 110);
    String second = UUID.randomUUID().toString();
    completed(second, "2026-10-11T00:00:00Z", "2026-10-11T01:00:00Z", 110, 120);
    call("POST", "/api/device/trips/" + first + "/confirm", device, details(1, "BUSINESS"), 200);
    assertThat(call("GET", "/api/trips?confirmed=false", owner, null, 200).path("total").asInt())
        .isEqualTo(1);
    assertThat(
            call("GET", "/api/device/trips?confirmed=true", device, null, 200)
                .path("items")
                .get(0)
                .path("id")
                .asText())
        .isEqualTo(first);
  }

  @Test
  void adminCanReturnClassificationToUnconfirmedWithoutApprovingIt() throws Exception {
    String id = approved("BUSINESS", START, END, 100, 110);
    var result = call("PATCH", "/api/trips/" + id, owner, details(3, "UNCONFIRMED"), 200);
    assertThat(result.path("driverConfirmedAt").isNull()).isTrue();
    assertThat(result.path("approvalStatus").asText()).isEqualTo("NOT_READY");
    call("POST", "/api/trips/" + id + "/approve", owner, Map.of("expectedVersion", 4), 409);
  }

  @Test
  void confirmedDriverCorrectionRequiresAnAuditReason() throws Exception {
    String id = UUID.randomUUID().toString();
    completed(id, START, END, 100, 110);
    call("POST", "/api/device/trips/" + id + "/confirm", device, details(1, "BUSINESS"), 200);
    var modified = details(2, "PERSONAL");
    modified.remove("reason");
    call("POST", "/api/device/trips/" + id + "/confirm", device, modified, 400);
  }

  @Test
  void collectionErrorsAreRetainedUntilExplicitlyResolved() throws Exception {
    String id = UUID.randomUUID().toString();
    var body = syncBody(1, 0, "COMPLETED", START, END, 100, 110);
    body.put("measurementSource", "PLEOS_ODOMETER");
    body.put("collectionIssues", List.of("SAMPLING_INTERRUPTED"));
    var first = call("PUT", "/api/device/trips/" + id, device, body, 200);
    assertThat(first.path("distanceKm").isNull()).isTrue();
    assertThat(first.path("measurementSource").asText()).isEqualTo("PLEOS_ODOMETER");
    var legacy = syncBody(2, 1, "COMPLETED", START, END, 100, 110);
    var preserved = call("PUT", "/api/device/trips/" + id, device, legacy, 200);
    assertThat(preserved.path("collectionIssues").get(0).asText())
        .isEqualTo("SAMPLING_INTERRUPTED");
    call("POST", "/api/device/trips/" + id + "/confirm", device, details(2, "BUSINESS"), 409);
    legacy.put("sourceRevision", 3);
    legacy.put("baseServerVersion", 2);
    legacy.put("collectionIssues", List.of());
    call("PUT", "/api/device/trips/" + id, device, legacy, 200);
    call("POST", "/api/device/trips/" + id + "/confirm", device, details(3, "BUSINESS"), 200);
    var audit = call("GET", "/api/trips/" + id + "/audit", owner, null, 200);
    assertThat(audit.get(2).path("beforeValue").path("collectionIssues").size()).isEqualTo(1);
    assertThat(audit.get(2).path("afterValue").path("collectionIssues").size()).isZero();
  }

  @Test
  void observedProgressCannotRegressOrDisappearOnCompletion() throws Exception {
    String id = UUID.randomUUID().toString();
    var body = syncBody(1, 0, "RUNNING", START, null, 100, null);
    body.put("lastObservedAt", "2026-10-10T00:30:00Z");
    body.put("lastOdometerKm", 120);
    call("PUT", "/api/device/trips/" + id, device, body, 200);
    body.put("sourceRevision", 2);
    body.put("baseServerVersion", 1);
    body.put("lastObservedAt", "2026-10-10T00:29:00Z");
    call("PUT", "/api/device/trips/" + id, device, body, 409);
    body.put("lastObservedAt", "2026-10-10T00:31:00Z");
    body.put("lastOdometerKm", 0);
    call("PUT", "/api/device/trips/" + id, device, body, 409);
    var ended =
        call(
            "PUT",
            "/api/device/trips/" + id,
            device,
            syncBody(2, 1, "COMPLETED", START, END, 100, 110),
            200);
    assertThat(ended.path("lastOdometerKm").asInt()).isEqualTo(120);
    assertThat(ended.path("distanceKm").isNull()).isTrue();
    completed(
        UUID.randomUUID().toString(), "2026-10-11T00:00:00Z", "2026-10-11T01:00:00Z", 130, 140);
    var gaps = call("GET", "/api/trips/gaps?month=2026-10", owner, null, 200);
    assertThat(gaps.path("knownGapKm").decimalValue()).isEqualByComparingTo("0");
    assertThat(gaps.path("items").get(1).path("issue").asText())
        .isEqualTo("INVALID_PREVIOUS_MEASUREMENT");
  }

  @Test
  void intermediateStopKeepsOneSessionUntilExplicitEndAndDraftNeedsConfirmation() throws Exception {
    String id = UUID.randomUUID().toString();
    var body = syncBody(1, 0, "RUNNING", START, null, 100, null);
    body.put("lastObservedAt", "2026-10-10T00:10:00Z");
    body.put("lastOdometerKm", 105);
    call("PUT", "/api/device/trips/" + id, device, body, 200);
    body.put("sourceRevision", 2);
    body.put("baseServerVersion", 1);
    body.put("lastObservedAt", "2026-10-10T00:40:00Z");
    var stopped = call("PUT", "/api/device/trips/" + id, device, body, 200);
    assertThat(stopped.path("state").asText()).isEqualTo("RUNNING");
    assertThat(stopped.path("durationSeconds").isNull()).isTrue();
    assertThat(stopped.path("observedDurationSeconds").asInt()).isEqualTo(2400);
    var ended =
        call(
            "PUT",
            "/api/device/trips/" + id,
            device,
            syncBody(3, 2, "COMPLETED", START, END, 100, 110),
            200);
    assertThat(ended.path("durationSeconds").asInt()).isEqualTo(3600);
    var draft = call("PATCH", "/api/device/trips/" + id, device, details(3, "UNCONFIRMED"), 200);
    assertThat(draft.path("driverConfirmedAt").isNull()).isTrue();
    call("POST", "/api/device/trips/" + id + "/confirm", device, details(4, "UNCONFIRMED"), 400);
    call("POST", "/api/device/trips/" + id + "/confirm", device, details(4, "BUSINESS"), 200);
    assertThat(call("GET", "/api/device/trips", device, null, 200).path("total").asInt())
        .isEqualTo(1);
  }

  @Test
  void preUpgradePayloadHashStillAcknowledgesOfflineRetry() throws Exception {
    String id = UUID.randomUUID().toString();
    var body = syncBody(1, 0, "COMPLETED", START, END, 100, 110);
    completed(id, START, END, 100, 110);
    // Literal layout generated by the original ten-field Sync record, before the new optional
    // fields.
    String oldJson =
        "{\"sourceRevision\":1,\"baseServerVersion\":0,\"driverId\":\""
            + driver
            + "\",\"startedAt\":\"2026-10-10T00:00:00Z\",\"endedAt\":\"2026-10-10T01:00:00Z\",\"lastObservedAt\":null,\"startOdometerKm\":100,\"endOdometerKm\":110,\"lastOdometerKm\":null,\"state\":\"COMPLETED\"}";
    jdbc.update(
        "UPDATE trips SET source_payload_hash=? WHERE id=?",
        io.autolog.auth.TokenCodec.hash(oldJson),
        UUID.fromString(id));
    assertThat(
            call("PUT", "/api/device/trips/" + id, device, body, 200).path("replayed").asBoolean())
        .isTrue();
    assertThat(call("GET", "/api/trips/" + id + "/audit", owner, null, 200).size()).isEqualTo(1);
  }

  @Test
  void missingReadingCannotMakeThePreviousReadingLookFresh() throws Exception {
    String id = UUID.randomUUID().toString();
    var body = syncBody(1, 0, "RUNNING", START, null, 100, null);
    body.put("lastObservedAt", "2026-10-10T00:10:00Z");
    body.put("lastOdometerKm", 105);
    call("PUT", "/api/device/trips/" + id, device, body, 200);
    body.put("sourceRevision", 2);
    body.put("baseServerVersion", 1);
    body.remove("lastOdometerKm");
    body.put("lastObservedAt", "2026-10-10T00:30:00Z");
    call("PUT", "/api/device/trips/" + id, device, body, 400);
    var retained = call("GET", "/api/device/trips/" + id, device, null, 200);
    assertThat(retained.path("lastObservedAt").asText()).isEqualTo("2026-10-10T00:10:00Z");
    assertThat(retained.path("lastOdometerKm").asInt()).isEqualTo(105);
  }

  @Test
  void driverDraftCannotEditApprovedRecordsOrAnotherVehicle() throws Exception {
    String id = approved("BUSINESS", START, END, 100, 110);
    call("PATCH", "/api/device/trips/" + id, device, details(3, "UNCONFIRMED"), 409);
    call("PATCH", "/api/device/trips/" + id, owner, details(3, "UNCONFIRMED"), 403);
    String second =
        call(
                "POST",
                "/api/vehicles",
                owner,
                Map.of("registrationNumber", "34나5678", "model", "다른 차량", "active", true),
                201)
            .path("id")
            .asText();
    String pair =
        call("POST", "/api/vehicles/" + second + "/pairing", owner, null, 200)
            .path("token")
            .asText();
    String anotherDevice =
        call("POST", "/api/device/pair", null, Map.of("pairingToken", pair), 200)
            .path("accessToken")
            .asText();
    call("PATCH", "/api/device/trips/" + id, anotherDevice, details(3, "UNCONFIRMED"), 404);
    assertThat(call("GET", "/api/trips/" + id, owner, null, 200).path("reportEligible").asBoolean())
        .isTrue();
  }

  @Test
  void nestedEarlierTripCannotInflateGapAfterAllOverlappingTripsEnd() throws Exception {
    completed(UUID.randomUUID().toString(), START, "2026-10-10T04:00:00Z", 100, 200);
    completed(UUID.randomUUID().toString(), END, "2026-10-10T01:30:00Z", 120, 130);
    completed(
        UUID.randomUUID().toString(), "2026-10-10T05:00:00Z", "2026-10-10T06:00:00Z", 210, 220);
    var gaps = call("GET", "/api/trips/gaps?month=2026-10", owner, null, 200);
    assertThat(gaps.path("knownGapKm").decimalValue()).isEqualByComparingTo("0");
    assertThat(gaps.path("items").get(2).path("issue").asText()).isEqualTo("OVERLAPPING_TRIPS");
  }
}
