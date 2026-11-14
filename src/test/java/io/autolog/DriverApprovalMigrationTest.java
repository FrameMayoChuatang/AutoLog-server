package io.autolog;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DriverApprovalMigrationTest extends ApiTestSupport {
  @Autowired DataSource dataSource;

  @Test
  void upgradeActivatesOnlyPendingDriversAndRunsOnce() {
    String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
    UUID companyId = UUID.randomUUID(), otherCompany = UUID.randomUUID();
    UUID vehicleId = UUID.randomUUID(), pendingId = UUID.randomUUID();
    try {
      migration(schema, "4").migrate();
      for (UUID company : new UUID[] {companyId, otherCompany}) {
        jdbc.update(
            "INSERT INTO " + schema + ".companies(id,name) VALUES(?,?)",
            company,
            "Migration fixture");
        for (String status : new String[] {"PENDING", "ACTIVE", "INACTIVE"}) {
          UUID id =
              company.equals(companyId) && status.equals("PENDING") ? pendingId : UUID.randomUUID();
          jdbc.update(
              "INSERT INTO "
                  + schema
                  + ".drivers(id,company_id,name,department,status,location_consent) VALUES(?,?,?,?,?,true)",
              id,
              company,
              status + " 운전자",
              "영업",
              status);
        }
      }
      jdbc.update(
          "INSERT INTO "
              + schema
              + ".vehicles(id,company_id,registration_number,model) VALUES(?,?,?,?)",
          vehicleId,
          companyId,
          "migration",
          "fixture");
      jdbc.update(
          "INSERT INTO "
              + schema
              + ".consent_events(id,company_id,driver_id,vehicle_id,granted,notice_version) VALUES(?,?,?,?,true,?)",
          UUID.randomUUID(),
          companyId,
          pendingId,
          vehicleId,
          "v1");
      jdbc.update(
          "INSERT INTO "
              + schema
              + ".trips(id,company_id,vehicle_id,driver_id,driver_name,driver_department,started_at,ended_at,state,approval_status,source_revision,source_payload_hash) VALUES(?,?,?,?,?,?,'2026-11-01T00:00:00Z','2026-11-01T01:00:00Z','COMPLETED','PENDING',1,?)",
          UUID.randomUUID(),
          companyId,
          vehicleId,
          pendingId,
          "기록 당시 이름",
          "기록 당시 부서",
          "0".repeat(64));

      var expectedDrivers = jdbc.queryForList("SELECT * FROM " + schema + ".drivers ORDER BY id");
      expectedDrivers.forEach(
          row -> {
            if (row.get("status").equals("PENDING")) row.put("status", "ACTIVE");
          });
      var originalTrips = jdbc.queryForList("SELECT * FROM " + schema + ".trips ORDER BY id");
      var originalConsents =
          jdbc.queryForList("SELECT * FROM " + schema + ".consent_events ORDER BY id");

      var upgrade = migration(schema, "5");
      assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
      assertThat(jdbc.queryForList("SELECT * FROM " + schema + ".drivers ORDER BY id"))
          .isEqualTo(expectedDrivers);
      assertThat(jdbc.queryForList("SELECT * FROM " + schema + ".trips ORDER BY id"))
          .isEqualTo(originalTrips);
      assertThat(jdbc.queryForList("SELECT * FROM " + schema + ".consent_events ORDER BY id"))
          .isEqualTo(originalConsents);

      // Later administrative decisions must not be overwritten on subsequent startups.
      jdbc.update("UPDATE " + schema + ".drivers SET status='PENDING' WHERE id=?", pendingId);
      assertThat(upgrade.migrate().migrationsExecuted).isZero();
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM " + schema + ".drivers WHERE id=?", String.class, pendingId))
          .isEqualTo("PENDING");
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  private Flyway migration(String schema, String version) {
    return Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .target(version)
        .load();
  }
}
