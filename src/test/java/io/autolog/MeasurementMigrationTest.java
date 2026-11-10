package io.autolog;

import static org.assertj.core.api.Assertions.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MeasurementMigrationTest extends ApiTestSupport {
  @Autowired DataSource dataSource;

  @Test
  void upgradeSealsBothCurrentAndPreviouslyConfirmedMeasurements() {
    String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
    UUID companyId = UUID.randomUUID(), vehicleId = UUID.randomUUID();
    UUID confirmed = UUID.randomUUID(), returned = UUID.randomUUID(), draft = UUID.randomUUID();
    Instant confirmation = Instant.parse("2026-10-10T02:00:00Z");
    try {
      Flyway.configure()
          .dataSource(dataSource)
          .schemas(schema)
          .defaultSchema(schema)
          .target("2")
          .load()
          .migrate();
      jdbc.update(
          "INSERT INTO " + schema + ".companies(id,name) VALUES(?,?)",
          companyId,
          "Migration fixture");
      jdbc.update(
          "INSERT INTO "
              + schema
              + ".vehicles(id,company_id,registration_number,model) VALUES(?,?,?,?)",
          vehicleId,
          companyId,
          "migration",
          "fixture");
      for (UUID id : new UUID[] {confirmed, returned, draft}) {
        jdbc.update(
            "INSERT INTO "
                + schema
                + ".trips(id,company_id,vehicle_id,started_at,ended_at,state,source_revision,source_payload_hash,driver_confirmed_at) VALUES(?,?,?,?::timestamptz,?::timestamptz,'COMPLETED',1,?,?)",
            id,
            companyId,
            vehicleId,
            "2026-10-10T00:00:00Z",
            "2026-10-10T01:00:00Z",
            "0".repeat(64),
            id.equals(confirmed) ? Timestamp.from(confirmation) : null);
      }
      jdbc.update(
          "INSERT INTO "
              + schema
              + ".trip_audit(id,company_id,trip_id,server_version,actor_vehicle_id,operation,after_value,created_at) VALUES(?,?,?,2,?,'DRIVER_CONFIRM','{}',?)",
          UUID.randomUUID(),
          companyId,
          returned,
          vehicleId,
          Timestamp.from(confirmation));
      Flyway.configure()
          .dataSource(dataSource)
          .schemas(schema)
          .defaultSchema(schema)
          .load()
          .migrate();
      for (UUID id : new UUID[] {confirmed, returned}) {
        assertThat(
                jdbc.queryForObject(
                        "SELECT measurement_locked_at FROM " + schema + ".trips WHERE id=?",
                        Timestamp.class,
                        id)
                    .toInstant())
            .isEqualTo(confirmation);
      }
      assertThat(
              jdbc.queryForObject(
                  "SELECT measurement_locked_at FROM " + schema + ".trips WHERE id=?",
                  Timestamp.class,
                  draft))
          .isNull();
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }
}
