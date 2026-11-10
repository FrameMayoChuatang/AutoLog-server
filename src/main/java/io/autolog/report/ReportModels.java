package io.autolog.report;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.autolog.fleet.FleetModels.Vehicle;
import io.autolog.trip.TripQueryModels.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public final class ReportModels {
  private ReportModels() {}

  public enum BoundaryStatus {
    MISSING,
    AT_BOUNDARY,
    LAST_KNOWN_REQUIRES_REVIEW,
    INVALID_REQUIRES_REVIEW
  }

  public record ReviewDistances(
      BigDecimal unconfirmedKnownKm, BigDecimal pendingApprovalKm, BigDecimal changesRequestedKm) {}

  public record OdometerBoundary(
      Instant boundaryAt,
      BigDecimal odometerKm,
      Instant observedAt,
      UUID tripId,
      BoundaryStatus status) {}

  public record VehicleSummary(
      UUID vehicleId,
      String registrationNumber,
      String model,
      int tripCount,
      int approvedCount,
      long approvedDrivingDays,
      BigDecimal approvedDurationSeconds,
      BigDecimal approvedKm,
      BigDecimal approvedCommuteKm,
      BigDecimal approvedBusinessKm,
      BigDecimal approvedPersonalKm,
      BigDecimal unreviewedKnownKm,
      @JsonUnwrapped ReviewDistances reviewDistances,
      OdometerBoundary previousMonthEnd,
      OdometerBoundary currentMonthEnd,
      List<String> reviewNotes) {}

  public record Statistics(
      String month,
      String timezone,
      int tripCount,
      BigDecimal knownCompletedKm,
      BigDecimal approvedKm,
      BigDecimal approvedCommuteKm,
      BigDecimal approvedBusinessKm,
      BigDecimal approvedPersonalKm,
      BigDecimal unreviewedKnownKm,
      @JsonUnwrapped ReviewDistances reviewDistances,
      BigDecimal incompleteObservedKm,
      BigDecimal approvedRecordBusinessRatioPercent,
      int approvedCount,
      long incompleteCount,
      long unconfirmedCount,
      long measurementIssueCount,
      long pendingApprovalCount,
      long changesRequestedCount,
      GapReport gaps,
      List<VehicleSummary> vehicles) {}

  public record WorkbookData(
      String month,
      String company,
      List<ReportTrip> rows,
      GapReport gaps,
      List<VehicleSummary> summaries,
      List<Vehicle> ntsVehicles) {}
}
