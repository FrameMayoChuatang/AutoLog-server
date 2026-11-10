package io.autolog.trip;

import static io.autolog.trip.TripModels.*;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public record TripResponse(
    UUID id,
    UUID companyId,
    UUID vehicleId,
    UUID driverId,
    Instant startedAt,
    Instant endedAt,
    Instant lastObservedAt,
    BigDecimal startOdometerKm,
    BigDecimal endOdometerKm,
    BigDecimal lastOdometerKm,
    State state,
    Classification classification,
    UUID purposeId,
    UUID clientId,
    String purposeText,
    String clientText,
    String note,
    String driverName,
    String driverDepartment,
    Instant driverConfirmedAt,
    Approval approvalStatus,
    UUID approvedBy,
    Instant approvedAt,
    long sourceRevision,
    long serverVersion,
    Instant createdAt,
    Instant updatedAt,
    Instant measurementLockedAt,
    MeasurementSource measurementSource,
    List<CollectionIssue> collectionIssues,
    List<String> qualityIssues,
    BigDecimal distanceKm,
    BigDecimal observedDistanceKm,
    BigDecimal durationSeconds,
    BigDecimal observedDurationSeconds,
    boolean reportEligible,
    @JsonInclude(JsonInclude.Include.NON_NULL) Boolean replayed) {
  public static TripResponse from(Trip trip) {
    return from(trip, null);
  }

  public static TripResponse replay(Trip trip) {
    return from(trip, true);
  }

  private static TripResponse from(Trip trip, Boolean replayed) {
    var issues = TripMeasurements.issues(trip);
    return new TripResponse(
        trip.id(),
        trip.companyId(),
        trip.vehicleId(),
        trip.driverId(),
        trip.startedAt(),
        trip.endedAt(),
        trip.lastObservedAt(),
        trip.startOdometerKm(),
        trip.endOdometerKm(),
        trip.lastOdometerKm(),
        trip.state(),
        trip.classification(),
        trip.purposeId(),
        trip.clientId(),
        trip.purposeText(),
        trip.clientText(),
        trip.note(),
        trip.driverName(),
        trip.driverDepartment(),
        trip.driverConfirmedAt(),
        trip.approvalStatus(),
        trip.approvedBy(),
        trip.approvedAt(),
        trip.sourceRevision(),
        trip.serverVersion(),
        trip.createdAt(),
        trip.updatedAt(),
        trip.measurementLockedAt(),
        trip.measurementSource(),
        trip.collectionIssues(),
        issues,
        TripMeasurements.distance(trip, issues),
        TripMeasurements.observedDistance(trip, issues),
        TripMeasurements.duration(trip),
        TripMeasurements.observedDuration(trip),
        TripMeasurements.reportEligible(trip, issues),
        replayed);
  }
}
