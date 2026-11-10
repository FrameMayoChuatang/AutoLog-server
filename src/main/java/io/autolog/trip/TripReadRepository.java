package io.autolog.trip;

import static io.autolog.common.JdbcValues.*;
import static io.autolog.trip.TripModels.*;
import static io.autolog.trip.TripQueryModels.*;

import io.autolog.common.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class TripReadRepository {
  private final JdbcExecutor db;
  private final TripRepository trips;
  private final JsonCodec json;

  public TripReadRepository(JdbcExecutor db, TripRepository trips, JsonCodec json) {
    this.db = db;
    this.trips = trips;
    this.json = json;
  }

  public PageResponse<TripResponse> list(UUID company, Filter filter) {
    UUID vehicleId = filter.vehicleId(), driverId = filter.driverId();
    Instant from = filter.from(), to = filter.to();
    State state = filter.state();
    Classification classification = filter.classification();
    Approval approval = filter.approval();
    Boolean confirmed = filter.confirmed();
    int page = filter.page(), size = filter.size();
    StringBuilder where = new StringBuilder(" WHERE company_id=?");
    List<Object> args = new ArrayList<>();
    args.add(company);
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
    if (confirmed != null)
      where.append(
          confirmed ? " AND driver_confirmed_at IS NOT NULL" : " AND driver_confirmed_at IS NULL");
    long total =
        db.one(
            "SELECT count(*) AS total FROM trips" + where,
            (r, n) -> r.getLong("total"),
            args.toArray());
    args.add(size);
    args.add((long) page * size);
    var items =
        db
            .list(
                "SELECT * FROM trips"
                    + where
                    + " ORDER BY started_at DESC,id DESC LIMIT ? OFFSET ?",
                trips.mapper(),
                args.toArray())
            .stream()
            .map(TripResponse::from)
            .toList();
    return new PageResponse<>(items, total, page, size);
  }

  private static void condition(StringBuilder sql, List<Object> args, String column, Object value) {
    if (value != null) {
      sql.append(" AND ").append(column).append("=?");
      args.add(value);
    }
  }

  public List<ReportTrip> monthly(
      UUID company, ReportPeriod period, UUID vehicleId, Integer limit) {
    String sql =
        """
        SELECT t.*, v.registration_number, v.model
        FROM trips t
        JOIN vehicles v ON v.company_id=t.company_id
        AND v.id=t.vehicle_id
        WHERE t.company_id=?
        AND t.started_at>=?
        AND t.started_at<?
        """;
    List<Object> args = new ArrayList<>(List.of(company, period.from(), period.to()));
    if (vehicleId != null) {
      sql += " AND t.vehicle_id=?";
      args.add(vehicleId);
    }
    sql += " ORDER BY t.vehicle_id,t.started_at,t.id";
    if (limit != null) {
      sql += " LIMIT ?";
      args.add(limit);
    }
    return db.list(
        sql,
        (r, n) ->
            ReportTrip.from(
                trips.mapper().mapRow(r, n),
                r.getString("registration_number"),
                r.getString("model")),
        args.toArray());
  }

  public List<GapBoundary> boundaries(UUID company, ReportPeriod period, UUID vehicleId) {
    String sql =
        """
        WITH ordered AS (
          SELECT id, vehicle_id, started_at, start_odometer_km,
                 lag(id) OVER w AS previous_id,
                 lag(ended_at) OVER w AS previous_end,
                 lag(end_odometer_km) OVER w AS previous_odometer,
                 lag(state) OVER w AS previous_state,
                 lag(start_odometer_km) OVER w AS previous_start_odometer,
                 lag(started_at) OVER w AS previous_started_at,
                 lag(collection_issues) OVER w AS previous_collection_issues,
                 lag(last_odometer_km) OVER w AS previous_last_odometer,
                 max(ended_at) OVER (
                   w ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
                 ) AS preceding_end
          FROM trips
          WHERE company_id=?
        """;
    List<Object> args = new ArrayList<>();
    args.add(company);
    if (vehicleId != null) {
      sql += " AND vehicle_id=?";
      args.add(vehicleId);
    }
    sql +=
        "\n"
            + """
          WINDOW w AS (PARTITION BY vehicle_id ORDER BY started_at, id)
        )
        SELECT * FROM ordered
        WHERE started_at>=? AND started_at<?
        ORDER BY vehicle_id, started_at, id
        """;
    args.add(period.from());
    args.add(period.to());
    return db.list(
        sql,
        (r, n) ->
            new GapBoundary(
                uuid(r, "id"),
                uuid(r, "vehicle_id"),
                instant(r, "started_at"),
                r.getBigDecimal("start_odometer_km"),
                uuid(r, "previous_id"),
                instant(r, "previous_end"),
                r.getBigDecimal("previous_odometer"),
                enumeration(r, "previous_state", State.class),
                r.getBigDecimal("previous_start_odometer"),
                instant(r, "previous_started_at"),
                json.readList(r.getString("previous_collection_issues"), CollectionIssue.class),
                r.getBigDecimal("previous_last_odometer"),
                instant(r, "preceding_end")),
        args.toArray());
  }

  public List<RecentDriver> recentDrivers(UUID company, UUID vehicle) {
    return db.list(
        """
        SELECT d.id, d.name, d.department, max(t.started_at) AS last_used_at
        FROM drivers d
        JOIN trips t ON t.company_id=d.company_id AND t.driver_id=d.id
        WHERE d.company_id=? AND t.vehicle_id=? AND d.status='ACTIVE'
        GROUP BY d.id, d.name, d.department
        ORDER BY last_used_at DESC, d.id
        LIMIT 5
        """,
        (r, n) ->
            new RecentDriver(
                uuid(r, "id"),
                r.getString("name"),
                r.getString("department"),
                instant(r, "last_used_at")),
        company,
        vehicle);
  }

  public List<Recommendation> recommendations(UUID company, UUID driver) {
    return db.list(
        """
        SELECT classification, purpose_id, purpose_text, client_id, client_text,
               max(started_at) AS last_used_at, count(*) AS usage_count
        FROM trips t
        WHERE t.company_id=? AND t.driver_id=?
          AND t.driver_confirmed_at IS NOT NULL
          AND t.state='COMPLETED' AND t.approval_status<>'CHANGES_REQUESTED'
          AND (t.purpose_text IS NOT NULL OR t.client_text IS NOT NULL)
          AND (t.purpose_id IS NULL OR EXISTS (
            SELECT 1 FROM purposes p
            WHERE p.company_id=t.company_id AND p.id=t.purpose_id AND p.active
          ))
          AND (t.client_id IS NULL OR EXISTS (
            SELECT 1 FROM clients c
            WHERE c.company_id=t.company_id AND c.id=t.client_id AND c.active
          ))
        GROUP BY classification, purpose_id, purpose_text, client_id, client_text
        ORDER BY last_used_at DESC, classification, purpose_text, client_text
        LIMIT 5
        """,
        (r, n) ->
            new Recommendation(
                enumeration(r, "classification", Classification.class),
                uuid(r, "purpose_id"),
                r.getString("purpose_text"),
                uuid(r, "client_id"),
                r.getString("client_text"),
                instant(r, "last_used_at"),
                r.getLong("usage_count")),
        company,
        driver);
  }
}
