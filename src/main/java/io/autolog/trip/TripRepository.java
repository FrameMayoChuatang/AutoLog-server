package io.autolog.trip;

import static io.autolog.common.JdbcValues.*;
import static io.autolog.trip.TripModels.*;
import static io.autolog.trip.TripRequests.*;

import io.autolog.auth.Actor;
import io.autolog.common.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class TripRepository {
  private final JdbcExecutor db;
  private final JsonCodec json;

  public TripRepository(JdbcExecutor db, JsonCodec json) {
    this.db = db;
    this.json = json;
  }

  public RowMapper<Trip> mapper() {
    return (r, n) ->
        new Trip(
            uuid(r, "id"),
            uuid(r, "company_id"),
            uuid(r, "vehicle_id"),
            uuid(r, "driver_id"),
            instant(r, "started_at"),
            instant(r, "ended_at"),
            instant(r, "last_observed_at"),
            r.getBigDecimal("start_odometer_km"),
            r.getBigDecimal("end_odometer_km"),
            r.getBigDecimal("last_odometer_km"),
            enumeration(r, "state", State.class),
            enumeration(r, "classification", Classification.class),
            uuid(r, "purpose_id"),
            uuid(r, "client_id"),
            r.getString("purpose_text"),
            r.getString("client_text"),
            r.getString("note"),
            r.getString("driver_name"),
            r.getString("driver_department"),
            instant(r, "driver_confirmed_at"),
            enumeration(r, "approval_status", Approval.class),
            uuid(r, "approved_by"),
            instant(r, "approved_at"),
            r.getLong("source_revision"),
            r.getString("source_payload_hash"),
            r.getLong("server_version"),
            instant(r, "created_at"),
            instant(r, "updated_at"),
            instant(r, "measurement_locked_at"),
            enumeration(r, "measurement_source", MeasurementSource.class),
            json.readList(r.getString("collection_issues"), CollectionIssue.class));
  }

  public Trip find(Actor actor, UUID id, boolean lock) {
    List<Object> args = new ArrayList<>(List.of(actor.companyId(), id));
    String sql = "SELECT * FROM trips WHERE company_id=? AND id=?";
    if (actor.isDevice()) {
      sql += " AND vehicle_id=?";
      args.add(actor.vehicleId());
    }
    return db.one(sql + (lock ? " FOR UPDATE" : ""), mapper(), args.toArray());
  }

  public Optional<Trip> lockDeviceTrip(Actor actor, UUID id) {
    return db.optional(
        "SELECT * FROM trips WHERE company_id=? AND vehicle_id=? AND id=? FOR UPDATE",
        mapper(),
        actor.companyId(),
        actor.vehicleId(),
        id);
  }

  public void insert(Actor actor, UUID id, SyncRequest body, String hash) {
    db.update(
        """
        INSERT INTO trips(id, company_id, vehicle_id, driver_id, started_at, ended_at,
        last_observed_at, start_odometer_km, end_odometer_km, last_odometer_km, state,
        source_revision, source_payload_hash) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        id,
        actor.companyId(),
        actor.vehicleId(),
        body.driverId(),
        body.startedAt(),
        body.endedAt(),
        body.lastObservedAt(),
        body.startOdometerKm(),
        body.endOdometerKm(),
        body.lastOdometerKm(),
        body.state().name(),
        body.sourceRevision(),
        hash);
  }

  public void updateMeasurements(Actor actor, UUID id, SyncRequest body, String hash) {
    db.update(
        """
        UPDATE trips
        SET driver_id=?, ended_at=?, last_observed_at=COALESCE(?, last_observed_at),
        start_odometer_km=?, end_odometer_km=?, last_odometer_km=COALESCE(?, last_odometer_km),
        state=?, source_revision=?, source_payload_hash=?, server_version=server_version+1,
        updated_at=now()
        WHERE company_id=?
        AND id=?
        """,
        body.driverId(),
        body.endedAt(),
        body.lastObservedAt(),
        body.startOdometerKm(),
        body.endOdometerKm(),
        body.lastOdometerKm(),
        body.state().name(),
        body.sourceRevision(),
        hash,
        actor.companyId(),
        id);
  }

  public void updateEvidence(Actor actor, UUID id, SyncRequest body) {
    if (body.measurementSource() != null)
      db.update(
          "UPDATE trips SET measurement_source=? WHERE company_id=? AND id=?",
          body.measurementSource().name(),
          actor.companyId(),
          id);
    if (body.collectionIssues() != null)
      db.update(
          "UPDATE trips SET collection_issues=?::jsonb WHERE company_id=? AND id=?",
          json.write(body.collectionIssues().stream().map(Enum::name).sorted().toList()),
          actor.companyId(),
          id);
  }

  public record Metadata(
      DetailsRequest request,
      String driverName,
      String department,
      String purpose,
      String client,
      String note,
      Instant confirmedAt,
      Approval approval) {}

  public void updateMetadata(Actor actor, UUID id, Metadata value) {
    var body = value.request();
    db.update(
        """
        UPDATE trips
        SET driver_id=?, driver_name=?, driver_department=?, classification=?, purpose_id=?,
        purpose_text=?, client_id=?, client_text=?, note=?, driver_confirmed_at=?,
        measurement_locked_at=COALESCE(measurement_locked_at, ?), approval_status=?, approved_by=NULL,
        approved_at=NULL, server_version=server_version+1, updated_at=now()
        WHERE company_id=?
        AND id=?
        """,
        body.driverId(),
        value.driverName(),
        value.department(),
        body.classification().name(),
        body.purposeId(),
        value.purpose(),
        body.clientId(),
        value.client(),
        value.note(),
        value.confirmedAt(),
        value.confirmedAt(),
        value.approval().name(),
        actor.companyId(),
        id);
  }

  public boolean overlapsApproved(Trip trip) {
    return db.optional(
            """
        SELECT id FROM trips
        WHERE company_id=? AND vehicle_id=? AND id<>? AND approval_status='APPROVED'
          AND ((started_at<? AND ended_at>?)
            OR (start_odometer_km<? AND end_odometer_km>?))
        LIMIT 1
        """,
            (r, n) -> uuid(r, "id"),
            trip.companyId(),
            trip.vehicleId(),
            trip.id(),
            trip.endedAt(),
            trip.startedAt(),
            trip.endOdometerKm(),
            trip.startOdometerKm())
        .isPresent();
  }

  public void approve(Actor actor, UUID id) {
    db.update(
        """
        UPDATE trips
        SET approval_status='APPROVED', approved_by=?, approved_at=now(),
        server_version=server_version+1, updated_at=now()
        WHERE company_id=?
        AND id=?
        """,
        actor.adminId(),
        actor.companyId(),
        id);
  }

  public void requestChanges(Actor actor, UUID id) {
    db.update(
        """
        UPDATE trips
        SET approval_status='CHANGES_REQUESTED', driver_confirmed_at=NULL, approved_by=NULL,
        approved_at=NULL, server_version=server_version+1, updated_at=now()
        WHERE company_id=?
        AND id=?
        """,
        actor.companyId(),
        id);
  }
}
