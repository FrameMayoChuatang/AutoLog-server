package io.autolog.trip;

import static io.autolog.trip.TripModels.*;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public final class TripQueryModels {
  private TripQueryModels() {}

  public record Filter(
      UUID vehicleId,
      UUID driverId,
      Instant from,
      Instant to,
      State state,
      Classification classification,
      Approval approval,
      Boolean confirmed,
      int page,
      int size) {}

  public record ReportTrip(
      Trip trip, TripResponse record, String registrationNumber, String model) {
    public static ReportTrip from(Trip trip, String registrationNumber, String model) {
      return new ReportTrip(trip, TripResponse.from(trip), registrationNumber, model);
    }
  }

  public record RecentDriver(UUID id, String name, String department, Instant lastUsedAt) {}

  public record Recommendation(
      Classification classification,
      UUID purposeId,
      String purposeText,
      UUID clientId,
      String clientText,
      Instant lastUsedAt,
      long usageCount) {}

  public record GapBoundary(
      UUID id,
      UUID vehicleId,
      Instant startedAt,
      BigDecimal startOdometerKm,
      UUID previousId,
      Instant previousEnd,
      BigDecimal previousOdometer,
      State previousState,
      BigDecimal previousStartOdometer,
      Instant previousStartedAt,
      List<CollectionIssue> previousCollectionIssues,
      BigDecimal previousLastOdometer,
      Instant precedingEnd) {}

  public record GapItem(@JsonUnwrapped GapBoundary boundary, String issue, BigDecimal distanceKm) {}

  public record GapReport(
      String month, BigDecimal knownGapKm, List<GapItem> items, long unknownBoundaryCount) {}
}
