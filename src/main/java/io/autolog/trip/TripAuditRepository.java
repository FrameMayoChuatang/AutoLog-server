package io.autolog.trip;

import static io.autolog.common.JdbcValues.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.autolog.auth.Actor;
import io.autolog.common.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class TripAuditRepository {
  public enum Operation {
    DEVICE_SYNC,
    DRIVER_CONFIRM,
    DRIVER_EDIT,
    ADMIN_EDIT,
    ADMIN_APPROVE,
    REQUEST_CHANGES
  }

  public record AuditEvent(
      UUID id,
      long serverVersion,
      UUID actorAdminId,
      UUID actorVehicleId,
      Operation operation,
      String reason,
      JsonNode beforeValue,
      JsonNode afterValue,
      Instant createdAt) {}

  private final JdbcExecutor db;
  private final JsonCodec json;
  private final TripRepository trips;

  public TripAuditRepository(JdbcExecutor db, JsonCodec json, TripRepository trips) {
    this.db = db;
    this.json = json;
    this.trips = trips;
  }

  public TripResponse record(
      Actor actor, UUID id, Operation operation, String reason, Trip before) {
    var after = trips.find(actor, id, false);
    var response = TripResponse.from(after);
    db.update(
        """
        INSERT INTO trip_audit(id, company_id, trip_id, server_version, actor_admin_id,
        actor_vehicle_id, operation, reason, before_value, after_value) VALUES(?, ?, ?, ?, ?, ?, ?, ?,
        ?::jsonb, ?::jsonb)
        """,
        UUID.randomUUID(),
        actor.companyId(),
        id,
        after.serverVersion(),
        actor.adminId(),
        actor.vehicleId(),
        operation.name(),
        reason,
        before == null ? null : json.write(TripResponse.from(before)),
        json.write(response));
    return response;
  }

  public List<AuditEvent> list(Actor actor, UUID id) {
    return db.list(
        """
        SELECT id, server_version, actor_admin_id, actor_vehicle_id, operation, reason, before_value,
        after_value, created_at
        FROM trip_audit
        WHERE company_id=?
        AND trip_id=?
        ORDER BY server_version
        """,
        (r, n) ->
            new AuditEvent(
                uuid(r, "id"),
                r.getLong("server_version"),
                uuid(r, "actor_admin_id"),
                uuid(r, "actor_vehicle_id"),
                enumeration(r, "operation", Operation.class),
                r.getString("reason"),
                json.readTree(r.getString("before_value")),
                json.readTree(r.getString("after_value")),
                instant(r, "created_at")),
        actor.companyId(),
        id);
  }
}
