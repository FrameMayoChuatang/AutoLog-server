package io.autolog.trip;

import static io.autolog.trip.TripModels.*;
import static io.autolog.trip.TripQueryModels.*;

import io.autolog.auth.Actor;
import io.autolog.common.*;
import io.autolog.fleet.FleetModels.DriverStatus;
import io.autolog.fleet.FleetRepository;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class TripQueryService {
  private final TripReadRepository reads;
  private final TripRepository trips;
  private final TripAuditRepository audit;
  private final FleetRepository fleet;

  public TripQueryService(
      TripReadRepository reads,
      TripRepository trips,
      TripAuditRepository audit,
      FleetRepository fleet) {
    this.reads = reads;
    this.trips = trips;
    this.audit = audit;
    this.fleet = fleet;
  }

  public TripResponse get(Actor actor, UUID id) {
    return TripResponse.from(trips.find(actor, id, false));
  }

  public List<TripAuditRepository.AuditEvent> audit(Actor actor, UUID id) {
    trips.find(actor, id, false);
    return audit.list(actor, id);
  }

  private void validateVehicle(Actor actor, UUID id) {
    if (id != null) fleet.vehicle(actor.companyId(), id, false);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PageResponse<TripResponse> list(Actor actor, Filter filter) {
    if (filter.page() < 0 || filter.page() > 100000 || filter.size() < 1 || filter.size() > 200)
      throw ApiException.bad("page는 0~100000, size는 1~200이어야 합니다.");
    if (filter.from() != null && filter.to() != null && !filter.from().isBefore(filter.to()))
      throw ApiException.bad("조회 종료 시각은 시작 이후여야 합니다.");
    UUID vehicle = filter.vehicleId();
    if (actor.isDevice()) {
      if (vehicle != null && !actor.vehicleId().equals(vehicle)) throw ApiException.missing();
      vehicle = actor.vehicleId();
    }
    validateVehicle(actor, vehicle);
    if (filter.driverId() != null) fleet.driver(actor.companyId(), filter.driverId(), false);
    return reads.list(
        actor.companyId(),
        new Filter(
            vehicle,
            filter.driverId(),
            filter.from(),
            filter.to(),
            filter.state(),
            filter.classification(),
            filter.approval(),
            filter.confirmed(),
            filter.page(),
            filter.size()));
  }

  public List<ReportTrip> monthly(Actor actor, String month, UUID vehicleId) {
    return monthly(actor, month, vehicleId, null);
  }

  public List<ReportTrip> monthly(Actor actor, String month, UUID vehicleId, Integer limit) {
    validateVehicle(actor, vehicleId);
    return reads.monthly(actor.companyId(), ReportPeriod.month(month), vehicleId, limit);
  }

  public GapReport gaps(Actor actor, String month, UUID vehicleId) {
    validateVehicle(actor, vehicleId);
    return TripGapCalculator.calculate(
        month, reads.boundaries(actor.companyId(), ReportPeriod.month(month), vehicleId));
  }

  public List<RecentDriver> recentDrivers(Actor actor) {
    actor.requireDevice();
    return reads.recentDrivers(actor.companyId(), actor.vehicleId());
  }

  public List<Recommendation> recommendations(Actor actor, UUID driverId) {
    if (fleet.driver(actor.companyId(), driverId, false).status() != DriverStatus.ACTIVE)
      throw ApiException.missing();
    return reads.recommendations(actor.companyId(), driverId);
  }
}
