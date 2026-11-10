package io.autolog.report;

import static io.autolog.report.ReportModels.*;

import io.autolog.auth.Actor;
import io.autolog.common.ApiException;
import io.autolog.common.ReportPeriod;
import io.autolog.fleet.FleetRepository;
import io.autolog.trip.TripQueryModels.ReportTrip;
import io.autolog.trip.TripQueryService;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ReportService {
  private static final int MAX_EXPORT_ROWS = 50000;
  private final ReportRepository repository;
  private final TripQueryService query;
  private final FleetRepository fleet;
  private final ReportWorkbookWriter writer;

  public ReportService(
      ReportRepository repository,
      TripQueryService query,
      FleetRepository fleet,
      ReportWorkbookWriter writer) {
    this.repository = repository;
    this.query = query;
    this.fleet = fleet;
    this.writer = writer;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Statistics stats(Actor actor, String month, UUID vehicleId) {
    var rows = query.monthly(actor, month, vehicleId);
    return ReportCalculator.statistics(
        month,
        rows,
        query.gaps(actor, month, vehicleId),
        vehicleSummaries(actor, month, vehicleId, rows));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public byte[] workbook(Actor actor, String month, UUID vehicleId, boolean nts) {
    var rows = query.monthly(actor, month, vehicleId, MAX_EXPORT_ROWS + 1);
    if (rows.size() > MAX_EXPORT_ROWS)
      throw ApiException.bad("엑셀은 한 번에 50,000건까지 출력할 수 있습니다. 차량을 선택해 주세요.");
    var gaps = query.gaps(actor, month, vehicleId);
    var company = repository.companyName(actor.companyId());
    LinkedHashSet<UUID> vehicleIds = new LinkedHashSet<>();
    rows.forEach(r -> vehicleIds.add(r.trip().vehicleId()));
    if (vehicleId != null) vehicleIds.add(vehicleId);
    var vehicles =
        vehicleIds.stream().map(id -> fleet.vehicle(actor.companyId(), id, false)).toList();
    return writer.write(
        new WorkbookData(
            month, company, rows, gaps, vehicleSummaries(actor, month, vehicleId, rows), vehicles),
        nts);
  }

  private List<VehicleSummary> vehicleSummaries(
      Actor actor, String month, UUID vehicleId, List<ReportTrip> rows) {
    var period = ReportPeriod.month(month);
    var vehicles =
        vehicleId == null
            ? fleet.vehicles(actor.companyId())
            : List.of(fleet.vehicle(actor.companyId(), vehicleId, false));
    return vehicles.stream()
        .map(
            vehicle -> {
              var selected =
                  rows.stream().filter(r -> vehicle.id().equals(r.trip().vehicleId())).toList();
              var previous =
                  ReportCalculator.boundary(
                      period.from(),
                      repository.latestApproved(actor.companyId(), vehicle.id(), period.from()));
              var current =
                  ReportCalculator.boundary(
                      period.to(),
                      repository.latestApproved(actor.companyId(), vehicle.id(), period.to()));
              return ReportCalculator.vehicle(vehicle, selected, previous, current);
            })
        .toList();
  }
}
