package io.autolog.trip;

import static io.autolog.trip.TripModels.*;
import static io.autolog.trip.TripQueryModels.*;

import java.math.BigDecimal;
import java.util.*;

public final class TripGapCalculator {
  private TripGapCalculator() {}

  public static GapReport calculate(String month, List<GapBoundary> boundaries) {
    var items =
        boundaries.stream().map(TripGapCalculator::evaluate).filter(Objects::nonNull).toList();
    var total =
        items.stream()
            .map(GapItem::distanceKm)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    return new GapReport(
        month, total, items, items.stream().filter(i -> i.distanceKm() == null).count());
  }

  private static GapItem evaluate(GapBoundary row) {
    String issue = null;
    BigDecimal distance = null,
        start = row.startOdometerKm(),
        previous = row.previousOdometer(),
        previousStart = row.previousStartOdometer();
    if (row.previousId() == null) issue = "NO_PREVIOUS_RECORDED_TRIP";
    else if (row.previousState() != State.COMPLETED) issue = "INCOMPLETE_PREVIOUS_TRIP";
    else if (row.previousEnd() == null
        || (row.precedingEnd() != null
            && ((row.precedingEnd()).isAfter(row.startedAt())
                || (row.precedingEnd()).isAfter(row.previousEnd())))) issue = "OVERLAPPING_TRIPS";
    else if (start == null || previous == null || start.signum() == 0)
      issue = "MISSING_OR_ZERO_ODOMETER";
    else if ((row.previousCollectionIssues() != null && !row.previousCollectionIssues().isEmpty())
        || (row.previousLastOdometer() != null
            && (row.previousLastOdometer().compareTo(previous) > 0
                || (previousStart != null
                    && row.previousLastOdometer().compareTo(previousStart) < 0)))
        || previousStart == null
        || previousStart.signum() == 0
        || previous.compareTo(previousStart) < 0
        || (previous.compareTo(previousStart) > 0
            && Objects.equals(row.previousStartedAt(), row.previousEnd())))
      issue = "INVALID_PREVIOUS_MEASUREMENT";
    else if (start.compareTo(previous) < 0) issue = "ODOMETER_REGRESSION";
    else if (start.compareTo(previous) > 0) {
      issue = "GAP_DETECTED";
      distance = start.subtract(previous);
    }
    return issue == null ? null : new GapItem(row, issue, distance);
  }
}
