package io.autolog;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;

class ApiContractTest extends ApiTestSupport {
  private static final String TRIP_FIELDS =
      "id companyId vehicleId driverId startedAt endedAt lastObservedAt startOdometerKm endOdometerKm lastOdometerKm state classification purposeId clientId purposeText clientText note driverName driverDepartment driverConfirmedAt approvalStatus approvedBy approvedAt sourceRevision serverVersion createdAt updatedAt measurementLockedAt measurementSource collectionIssues qualityIssues distanceKm observedDistanceKm durationSeconds observedDurationSeconds reportEligible";

  private static void fields(JsonNode value, String expected) {
    Set<String> actual = new HashSet<>();
    value.fieldNames().forEachRemaining(actual::add);
    assertThat(actual).containsExactlyInAnyOrder(expected.split(" "));
  }

  @Test
  void tripAndAuditKeepTheFlatPublicContractWithoutStorageSecrets() throws Exception {
    String id = UUID.randomUUID().toString();
    var body =
        syncBody(1, 0, "COMPLETED", "2026-10-10T00:00:00Z", "2026-10-10T01:00:00Z", 100, 110);
    var trip = call("PUT", "/api/device/trips/" + id, device, body, 200);
    fields(trip, TRIP_FIELDS);
    assertThat(trip.path("sourcePayloadHash").isMissingNode()).isTrue();
    var replay = call("PUT", "/api/device/trips/" + id, device, body, 200);
    fields(replay, TRIP_FIELDS + " replayed");
    fields(call("GET", "/api/trips/" + id, owner, null, 200), TRIP_FIELDS);
    var audit = call("GET", "/api/trips/" + id + "/audit", owner, null, 200).get(0);
    fields(
        audit,
        "id serverVersion actorAdminId actorVehicleId operation reason beforeValue afterValue createdAt");
    fields(audit.path("afterValue"), TRIP_FIELDS);
    assertThat(audit.path("beforeValue").isNull()).isTrue();
  }

  @Test
  void identityAndFleetContractsDoNotExposePersistenceFields() throws Exception {
    fields(
        call("GET", "/api/auth/me", owner, null, 200),
        "id companyId email name role active createdAt");
    fields(
        call("GET", "/api/auth/me", device, null, 200),
        "id companyId registrationNumber model active");
    fields(
        call("GET", "/api/vehicles/" + vehicle, owner, null, 200),
        "id companyId registrationNumber model active createdAt");
    fields(
        call("GET", "/api/drivers", owner, null, 200).get(0),
        "id companyId name department status locationConsent createdAt");
    fields(
        call("GET", "/api/catalog/clients", owner, null, 200).get(0),
        "id companyId name address description active createdAt");
    fields(
        call("GET", "/api/catalog/purposes", owner, null, 200).get(0),
        "id companyId name active createdAt");
    fields(
        call("GET", "/api/admins", owner, null, 200).get(0),
        "id email name role active passwordConfigured createdAt");
  }

  @Test
  void statisticsKeepFlatReviewFieldsAndOpenApiDocumentsTripProperties() throws Exception {
    var stats = call("GET", "/api/statistics?month=2026-10", owner, null, 200);
    fields(
        stats,
        "month timezone tripCount knownCompletedKm approvedKm approvedCommuteKm approvedBusinessKm approvedPersonalKm unreviewedKnownKm unconfirmedKnownKm pendingApprovalKm changesRequestedKm incompleteObservedKm approvedRecordBusinessRatioPercent approvedCount incompleteCount unconfirmedCount measurementIssueCount pendingApprovalCount changesRequestedCount gaps vehicles");
    fields(
        stats.path("vehicles").get(0),
        "vehicleId registrationNumber model tripCount approvedCount approvedDrivingDays approvedDurationSeconds approvedKm approvedCommuteKm approvedBusinessKm approvedPersonalKm unreviewedKnownKm unconfirmedKnownKm pendingApprovalKm changesRequestedKm previousMonthEnd currentMonthEnd reviewNotes");
    var schema = call("GET", "/api/docs", null, null, 200);
    String ref =
        schema
            .path("paths")
            .path("/api/trips/{id}")
            .path("get")
            .path("responses")
            .path("200")
            .path("content")
            .path("*/*")
            .path("schema")
            .path("$ref")
            .asText();
    // Resolve the generated response schema instead of coupling clients to a Java class name.
    if (ref.isEmpty())
      ref =
          schema
              .path("paths")
              .path("/api/trips/{id}")
              .path("get")
              .path("responses")
              .path("200")
              .path("content")
              .path("application/json")
              .path("schema")
              .path("$ref")
              .asText();
    assertThat(ref).isNotBlank();
    var properties =
        schema
            .path("components")
            .path("schemas")
            .path(ref.substring(ref.lastIndexOf('/') + 1))
            .path("properties");
    assertThat(properties.has("id")).isTrue();
    assertThat(properties.has("driverName")).isTrue();
    assertThat(properties.has("sourcePayloadHash")).isFalse();
    assertThat(schema.path("components").path("schemas").path("CurrentUser").path("anyOf").size())
        .isEqualTo(2);
    assertThat(schema.path("components").path("schemas").path("CatalogItem").path("anyOf").size())
        .isEqualTo(2);
  }
}
