package io.autolog.trip;

import static io.autolog.trip.TripModels.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class TripMeasurements {
  private TripMeasurements() {}

  public static List<String> issues(Trip trip) {
    List<String> issues = new ArrayList<>();
    if (trip.state() != State.COMPLETED) issues.add("INCOMPLETE");
    BigDecimal start = trip.startOdometerKm(), end = trip.endOdometerKm();
    if (start == null) issues.add("MISSING_START_ODOMETER");
    else if (start.signum() == 0) issues.add("ZERO_START_ODOMETER");
    if (end == null) issues.add("MISSING_END_ODOMETER");
    if (start != null && end != null && end.compareTo(start) < 0) issues.add("ODOMETER_REGRESSION");
    if (start != null
        && end != null
        && end.compareTo(start) > 0
        && Objects.equals(trip.startedAt(), trip.endedAt()))
      issues.add("ZERO_DURATION_WITH_DISTANCE");
    BigDecimal last = trip.lastOdometerKm();
    if (last != null && start != null && last.compareTo(start) < 0)
      issues.add("LAST_ODOMETER_BEFORE_START");
    if (last != null && end != null && last.compareTo(end) > 0)
      issues.add("LAST_ODOMETER_AFTER_END");
    for (var issue : trip.collectionIssues())
      if (!issues.contains(issue.toString())) issues.add(issue.toString());
    return issues;
  }

  static BigDecimal distance(Trip trip, List<String> issues) {
    return issues.isEmpty() ? trip.endOdometerKm().subtract(trip.startOdometerKm()) : null;
  }

  static BigDecimal observedDistance(Trip trip, List<String> issues) {
    if (!issues.stream().allMatch(i -> i.equals("INCOMPLETE") || i.equals("MISSING_END_ODOMETER")))
      return null;
    var start = trip.startOdometerKm();
    var last = trip.lastOdometerKm();
    return start != null && start.signum() > 0 && last != null && last.compareTo(start) >= 0
        ? last.subtract(start)
        : null;
  }

  public static BigDecimal duration(Trip trip) {
    return trip.state() == State.COMPLETED && trip.endedAt() != null
        ? seconds(trip.startedAt(), trip.endedAt())
        : null;
  }

  public static BigDecimal observedDuration(Trip trip) {
    return trip.lastObservedAt() == null ? null : seconds(trip.startedAt(), trip.lastObservedAt());
  }

  public static boolean reportEligible(Trip trip) {
    return reportEligible(trip, issues(trip));
  }

  static boolean reportEligible(Trip trip, List<String> issues) {
    return issues.isEmpty()
        && trip.approvalStatus() == Approval.APPROVED
        && trip.driverConfirmedAt() != null;
  }

  private static BigDecimal seconds(Instant from, Instant to) {
    Duration duration = Duration.between(from, to);
    return BigDecimal.valueOf(duration.getSeconds()).add(BigDecimal.valueOf(duration.getNano(), 9));
  }
}
