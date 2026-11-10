package io.autolog;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocationBoundaryApiTest extends ApiTestSupport {
  @Test
  void locationFieldsAreRejectedEvenWithConsentWithoutPersistingTrip() throws Exception {
    String trip = UUID.randomUUID().toString();
    for (boolean granted : new boolean[] {false, true}) {
      call(
          "POST",
          "/api/device/drivers/" + driver + "/consent",
          device,
          Map.of("granted", granted, "noticeVersion", "v1"),
          200);
      var locationFields =
          Map.of(
              "arrivalLocation",
              Map.of("latitude", 37.5, "longitude", 127.0),
              "latitude",
              37.5,
              "longitude",
              127.0,
              "route",
              java.util.List.of(Map.of("latitude", 37.5, "longitude", 127.0)));
      for (var field : locationFields.entrySet()) {
        var body =
            syncBody(1, 0, "COMPLETED", "2026-10-10T00:00:00Z", "2026-10-10T01:00:00Z", 1000, 1010);
        body.put(field.getKey(), field.getValue());
        call("PUT", "/api/device/trips/" + trip, device, body, 400);
      }
      call("GET", "/api/device/trips/" + trip, device, null, 404);
      assertThat(jdbc.queryForObject("SELECT count(*) FROM trips", Integer.class)).isZero();
    }
  }

  @Test
  void recordingApprovalAndRecentRecommendationsWorkWithoutLocationConsent() throws Exception {
    assertThat(
            call("GET", "/api/device/drivers", device, null, 200)
                .get(0)
                .path("locationConsent")
                .asBoolean())
        .isFalse();
    String trip = approved("BUSINESS", "2026-10-10T00:00:00Z", "2026-10-10T01:00:00Z", 1000, 1010);
    assertThat(
            call("GET", "/api/device/trips/" + trip, device, null, 200)
                .path("reportEligible")
                .asBoolean())
        .isTrue();
    var recommendations =
        call("GET", "/api/device/drivers/" + driver + "/recommendations", device, null, 200);
    assertThat(recommendations).hasSize(1);
    assertThat(recommendations.get(0).path("purposeId").asText()).isEqualTo(purpose);
    assertThat(recommendations.get(0).path("clientId").asText()).isEqualTo(client);
    assertThat(
            call("GET", "/api/statistics?month=2026-10", owner, null, 200)
                .path("approvedKm")
                .asDouble())
        .isEqualTo(10);
  }
}
