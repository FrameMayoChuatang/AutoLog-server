package io.autolog.trip;

import static io.autolog.common.Db.*;
import static io.autolog.trip.TripModels.*;

import io.autolog.auth.Actor;
import io.autolog.common.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class TripQueryService {
  public static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Seoul");

  public record Period(Instant from, Instant to) {}

  public static Period month(String month) {
    if (month == null || !month.matches("[0-9]{4}-[0-9]{2}") || month.startsWith("0000"))
      throw ApiException.bad("month는 YYYY-MM 형식이어야 합니다.");
    try {
      var parsed = YearMonth.parse(month);
      return new Period(
          parsed.atDay(1).atStartOfDay(REPORT_ZONE).toInstant(),
          parsed.plusMonths(1).atDay(1).atStartOfDay(REPORT_ZONE).toInstant());
    } catch (DateTimeParseException | NullPointerException ex) {
      throw ApiException.bad("month는 YYYY-MM 형식이어야 합니다.");
    }
  }

  private final Db db;

  public TripQueryService(Db db) {
    this.db = db;
  }

  private void vehicle(Actor actor, UUID vehicle) {
    if (vehicle != null)
      db.one("SELECT id FROM vehicles WHERE company_id=? AND id=?", actor.companyId(), vehicle);
  }

  public Map<String, Object> list(
      Actor actor,
      UUID vehicleId,
      UUID driverId,
      Instant from,
      Instant to,
      State state,
      Classification classification,
      Approval approval,
      int page,
      int size) {
    if (page < 0 || page > 100000 || size < 1 || size > 200)
      throw ApiException.bad("page는 0~100000, size는 1~200이어야 합니다.");
    if (from != null && to != null && !from.isBefore(to))
      throw ApiException.bad("조회 종료 시각은 시작 이후여야 합니다.");
    if (actor.isDevice()) {
      if (vehicleId != null && !actor.vehicleId().equals(vehicleId)) throw ApiException.missing();
      vehicleId = actor.vehicleId();
    }
    vehicle(actor, vehicleId);
    if (driverId != null)
      db.one("SELECT id FROM drivers WHERE company_id=? AND id=?", actor.companyId(), driverId);
    StringBuilder where = new StringBuilder(" WHERE company_id=?");
    List<Object> args = new ArrayList<>();
    args.add(actor.companyId());
    condition(where, args, "vehicle_id", vehicleId);
    condition(where, args, "driver_id", driverId);
    if (from != null) {
      where.append(" AND started_at>=?");
      args.add(from);
    }
    if (to != null) {
      where.append(" AND started_at<?");
      args.add(to);
    }
    condition(where, args, "state", state == null ? null : state.name());
    condition(where, args, "classification", classification == null ? null : classification.name());
    condition(where, args, "approval_status", approval == null ? null : approval.name());
    long total =
        number(db.one("SELECT count(*) AS total FROM trips" + where, args.toArray()), "total");
    args.add(size);
    args.add((long) page * size);
    var items =
        db
            .list(
                "SELECT * FROM trips"
                    + where
                    + " ORDER BY started_at DESC,id DESC LIMIT ? OFFSET ?",
                args.toArray())
            .stream()
            .map(TripService::decorate)
            .toList();
    return Map.of("items", items, "total", total, "page", page, "size", size);
  }

  private static void condition(StringBuilder sql, List<Object> args, String column, Object value) {
    if (value != null) {
      sql.append(" AND ").append(column).append("=?");
      args.add(value);
    }
  }

  public List<Map<String, Object>> monthly(Actor actor, String month, UUID vehicleId) {
    vehicle(actor, vehicleId);
    Period period = month(month);
    String sql =
        "SELECT t.*,v.registration_number,v.model FROM trips t JOIN vehicles v ON v.company_id=t.company_id AND v.id=t.vehicle_id WHERE t.company_id=? AND t.started_at>=? AND t.started_at<?";
    List<Object> args = new ArrayList<>(List.of(actor.companyId(), period.from(), period.to()));
    if (vehicleId != null) {
      sql += " AND t.vehicle_id=?";
      args.add(vehicleId);
    }
    return db.list(sql + " ORDER BY t.vehicle_id,t.started_at,t.id", args.toArray()).stream()
        .map(TripService::decorate)
        .toList();
  }

  public Map<String, Object> gaps(Actor actor, String month, UUID vehicleId) {
    vehicle(actor, vehicleId);
    Period period = month(month);
    String sql =
        "WITH ordered AS (SELECT id,vehicle_id,started_at,start_odometer_km,lag(id) OVER w AS previous_id,lag(ended_at) OVER w AS previous_end,lag(end_odometer_km) OVER w AS previous_odometer,lag(state) OVER w AS previous_state FROM trips WHERE company_id=?";
    List<Object> args = new ArrayList<>();
    args.add(actor.companyId());
    if (vehicleId != null) {
      sql += " AND vehicle_id=?";
      args.add(vehicleId);
    }
    sql +=
        " WINDOW w AS (PARTITION BY vehicle_id ORDER BY started_at,id)) SELECT * FROM ordered WHERE started_at>=? AND started_at<? ORDER BY vehicle_id,started_at,id";
    args.add(period.from());
    args.add(period.to());
    var rows = db.list(sql, args.toArray());
    List<Map<String, Object>> gaps = new ArrayList<>();
    BigDecimal total = BigDecimal.ZERO;
    for (var row : rows) {
      String issue = null;
      BigDecimal distance = null,
          start = decimal(row, "startOdometerKm"),
          previous = decimal(row, "previousOdometer");
      if (row.get("previousId") == null) issue = "NO_PREVIOUS_RECORDED_TRIP";
      else if (!"COMPLETED".equals(text(row, "previousState"))) issue = "INCOMPLETE_PREVIOUS_TRIP";
      else if (row.get("previousEnd") == null
          || ((Instant) row.get("previousEnd")).isAfter((Instant) row.get("startedAt")))
        issue = "OVERLAPPING_TRIPS";
      else if (start == null || previous == null || start.signum() == 0)
        issue = "MISSING_OR_ZERO_ODOMETER";
      else if (start.compareTo(previous) < 0) issue = "ODOMETER_REGRESSION";
      else if (start.compareTo(previous) > 0) {
        issue = "GAP_DETECTED";
        distance = start.subtract(previous);
        total = total.add(distance);
      }
      if (issue != null) {
        var gap = new LinkedHashMap<>(row);
        gap.put("issue", issue);
        gap.put("distanceKm", distance);
        gaps.add(gap);
      }
    }
    return Map.of(
        "month",
        month,
        "knownGapKm",
        total,
        "items",
        gaps,
        "unknownBoundaryCount",
        gaps.stream().filter(g -> g.get("distanceKm") == null).count());
  }

  public List<Map<String, Object>> recentDrivers(Actor actor) {
    actor.requireDevice();
    return db.list(
        "SELECT d.id,d.name,d.department,max(t.started_at) AS last_used_at FROM drivers d JOIN trips t ON t.company_id=d.company_id AND t.driver_id=d.id WHERE d.company_id=? AND t.vehicle_id=? AND d.status='ACTIVE' GROUP BY d.id,d.name,d.department ORDER BY last_used_at DESC,d.id LIMIT 5",
        actor.companyId(),
        actor.vehicleId());
  }

  public List<Map<String, Object>> recommendations(Actor actor, UUID driverId) {
    db.one(
        "SELECT id FROM drivers WHERE company_id=? AND id=? AND status='ACTIVE'",
        actor.companyId(),
        driverId);
    return db.list(
        "SELECT classification,purpose_id,purpose_text,client_id,client_text,max(started_at) AS last_used_at,count(*) AS usage_count FROM trips t WHERE t.company_id=? AND t.driver_id=? AND t.driver_confirmed_at IS NOT NULL AND t.state='COMPLETED' AND t.approval_status<>'CHANGES_REQUESTED' AND (t.purpose_text IS NOT NULL OR t.client_text IS NOT NULL) AND (t.purpose_id IS NULL OR EXISTS(SELECT 1 FROM purposes p WHERE p.company_id=t.company_id AND p.id=t.purpose_id AND p.active)) AND (t.client_id IS NULL OR EXISTS(SELECT 1 FROM clients c WHERE c.company_id=t.company_id AND c.id=t.client_id AND c.active)) GROUP BY classification,purpose_id,purpose_text,client_id,client_text ORDER BY last_used_at DESC,classification,purpose_text,client_text LIMIT 5",
        actor.companyId(),
        driverId);
  }
}
