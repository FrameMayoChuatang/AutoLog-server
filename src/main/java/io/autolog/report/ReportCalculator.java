package io.autolog.report;

import static io.autolog.report.ReportModels.*;
import static io.autolog.trip.TripModels.*;

import io.autolog.common.ReportPeriod;
import io.autolog.fleet.FleetModels.Vehicle;
import io.autolog.trip.*;
import io.autolog.trip.TripQueryModels.*;
import java.math.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

public final class ReportCalculator {
  private ReportCalculator() {}

  public static BigDecimal sum(List<ReportTrip> rows, Function<TripResponse, BigDecimal> value) {
    return rows.stream()
        .map(ReportTrip::record)
        .map(value)
        .filter(Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  public static List<ReportTrip> approved(List<ReportTrip> rows) {
    return rows.stream().filter(r -> r.record().reportEligible()).toList();
  }

  public static List<ReportTrip> category(List<ReportTrip> rows, Classification classification) {
    return rows.stream().filter(r -> r.trip().classification() == classification).toList();
  }

  private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
    return denominator.signum() == 0
        ? null
        : numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 2, RoundingMode.HALF_UP);
  }

  private static ReviewDistances reviewDistances(List<ReportTrip> rows) {
    return new ReviewDistances(
        sum(
            rows.stream().filter(r -> r.trip().driverConfirmedAt() == null).toList(),
            TripResponse::distanceKm),
        sum(
            rows.stream().filter(r -> r.trip().approvalStatus() == Approval.PENDING).toList(),
            TripResponse::distanceKm),
        sum(
            rows.stream()
                .filter(r -> r.trip().approvalStatus() == Approval.CHANGES_REQUESTED)
                .toList(),
            TripResponse::distanceKm));
  }

  public static Statistics statistics(
      String month, List<ReportTrip> rows, GapReport gaps, List<VehicleSummary> vehicles) {
    var selected = approved(rows);
    var approvedKm = sum(selected, TripResponse::distanceKm);
    var known = sum(rows, TripResponse::distanceKm);
    var commute = sum(category(selected, Classification.COMMUTE), TripResponse::distanceKm);
    var business = sum(category(selected, Classification.BUSINESS), TripResponse::distanceKm);
    return new Statistics(
        month,
        ReportPeriod.ZONE.toString(),
        rows.size(),
        known,
        approvedKm,
        commute,
        business,
        sum(category(selected, Classification.PERSONAL), TripResponse::distanceKm),
        known.subtract(approvedKm),
        reviewDistances(rows),
        sum(
            rows.stream().filter(r -> r.trip().state() != State.COMPLETED).toList(),
            TripResponse::observedDistanceKm),
        ratio(commute.add(business), approvedKm),
        selected.size(),
        rows.stream().filter(r -> r.trip().state() != State.COMPLETED).count(),
        rows.stream().filter(r -> r.trip().driverConfirmedAt() == null).count(),
        rows.stream()
            .filter(r -> r.trip().state() == State.COMPLETED && r.record().distanceKm() == null)
            .count(),
        rows.stream().filter(r -> r.trip().approvalStatus() == Approval.PENDING).count(),
        rows.stream().filter(r -> r.trip().approvalStatus() == Approval.CHANGES_REQUESTED).count(),
        gaps,
        vehicles);
  }

  public static OdometerBoundary boundary(Instant boundary, Optional<Trip> candidate) {
    if (candidate.isEmpty())
      return new OdometerBoundary(boundary, null, null, null, BoundaryStatus.MISSING);
    var trip = candidate.get();
    boolean eligible = TripMeasurements.reportEligible(trip);
    var status =
        !eligible
            ? BoundaryStatus.INVALID_REQUIRES_REVIEW
            : boundary.equals(trip.endedAt())
                ? BoundaryStatus.AT_BOUNDARY
                : BoundaryStatus.LAST_KNOWN_REQUIRES_REVIEW;
    return new OdometerBoundary(
        boundary, eligible ? trip.endOdometerKm() : null, trip.endedAt(), trip.id(), status);
  }

  public static VehicleSummary vehicle(
      Vehicle vehicle, List<ReportTrip> rows, OdometerBoundary previous, OdometerBoundary current) {
    var selected = approved(rows);
    var approvedKm = sum(selected, TripResponse::distanceKm);
    long days =
        selected.stream()
            .map(r -> r.trip().startedAt().atZone(ReportPeriod.ZONE).toLocalDate())
            .distinct()
            .count();
    List<String> notes = new ArrayList<>();
    if (previous.status() != BoundaryStatus.AT_BOUNDARY)
      notes.add("전월 말 계기판 확인 필요 (" + previous.status() + ")");
    if (current.status() != BoundaryStatus.AT_BOUNDARY)
      notes.add("당월 말 계기판 확인 필요 (" + current.status() + ")");
    if (previous.odometerKm() != null
        && current.odometerKm() != null
        && current.odometerKm().compareTo(previous.odometerKm()) < 0)
      notes.add("월말 기준 누적거리 역전 확인 필요");
    if (rows.isEmpty()) notes.add("해당 월 수신 운행 없음 — 미운행을 보장하지 않음");
    if (rows.size() != selected.size())
      notes.add("미승인·미확인·미완료 또는 측정 오류 기록 제외: " + (rows.size() - selected.size()) + "건");
    notes.add("기록 간 공백 시트/API와 비교 필요; 월말 기준값 차이를 업무거리로 자동 분류하지 않음");
    return new VehicleSummary(
        vehicle.id(),
        vehicle.registrationNumber(),
        vehicle.model(),
        rows.size(),
        selected.size(),
        days,
        sum(selected, TripResponse::durationSeconds),
        approvedKm,
        sum(category(selected, Classification.COMMUTE), TripResponse::distanceKm),
        sum(category(selected, Classification.BUSINESS), TripResponse::distanceKm),
        sum(category(selected, Classification.PERSONAL), TripResponse::distanceKm),
        sum(rows, TripResponse::distanceKm).subtract(approvedKm),
        reviewDistances(rows),
        previous,
        current,
        notes);
  }
}
