package io.autolog.fleet;

import static io.autolog.common.JdbcValues.*;
import static io.autolog.fleet.FleetModels.*;

import io.autolog.common.JdbcExecutor;
import java.util.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class FleetRepository {
  public static final RowMapper<Vehicle> VEHICLE =
      (r, n) ->
          new Vehicle(
              uuid(r, "id"),
              uuid(r, "company_id"),
              r.getString("registration_number"),
              r.getString("model"),
              r.getBoolean("active"),
              instant(r, "created_at"));
  public static final RowMapper<Driver> DRIVER =
      (r, n) ->
          new Driver(
              uuid(r, "id"),
              uuid(r, "company_id"),
              r.getString("name"),
              r.getString("department"),
              enumeration(r, "status", DriverStatus.class),
              r.getBoolean("location_consent"),
              instant(r, "created_at"));
  private static final RowMapper<CatalogItem> CLIENT =
      (r, n) ->
          new Client(
              uuid(r, "id"),
              uuid(r, "company_id"),
              r.getString("name"),
              r.getString("address"),
              r.getString("description"),
              r.getBoolean("active"),
              instant(r, "created_at"));
  private static final RowMapper<CatalogItem> PURPOSE =
      (r, n) ->
          new Purpose(
              uuid(r, "id"),
              uuid(r, "company_id"),
              r.getString("name"),
              r.getBoolean("active"),
              instant(r, "created_at"));
  private final JdbcExecutor db;

  public FleetRepository(JdbcExecutor db) {
    this.db = db;
  }

  public List<Vehicle> vehicles(UUID company) {
    return db.list(
        "SELECT * FROM vehicles WHERE company_id=? ORDER BY registration_number,id",
        VEHICLE,
        company);
  }

  public Vehicle vehicle(UUID company, UUID id, boolean lock) {
    return db.one(
        "SELECT * FROM vehicles WHERE company_id=? AND id=?" + (lock ? " FOR UPDATE" : ""),
        VEHICLE,
        company,
        id);
  }

  public void createVehicle(UUID company, UUID id, FleetRequests.VehicleRequest body) {
    db.update(
        "INSERT INTO vehicles(id,company_id,registration_number,model,active) VALUES(?,?,?,?,?)",
        id,
        company,
        body.registrationNumber().strip(),
        body.model().strip(),
        body.active());
  }

  public void updateVehicle(UUID company, UUID id, FleetRequests.VehicleRequest body) {
    db.update(
        "UPDATE vehicles SET registration_number=?,model=?,active=? WHERE company_id=? AND id=?",
        body.registrationNumber().strip(),
        body.model().strip(),
        body.active(),
        company,
        id);
  }

  public List<Driver> drivers(UUID company, String query, boolean device) {
    return db.list(
        "SELECT * FROM drivers WHERE company_id=? AND (name ILIKE ? OR department ILIKE ?)"
            + (device ? " AND status<>'INACTIVE'" : "")
            + " ORDER BY name,department,id",
        DRIVER,
        company,
        "%" + query + "%",
        "%" + query + "%");
  }

  public Driver driver(UUID company, UUID id, boolean lock) {
    return db.one(
        "SELECT * FROM drivers WHERE company_id=? AND id=?" + (lock ? " FOR UPDATE" : ""),
        DRIVER,
        company,
        id);
  }

  public Driver shareDriver(UUID company, UUID id) {
    return db.one(
        "SELECT * FROM drivers WHERE company_id=? AND id=? FOR SHARE", DRIVER, company, id);
  }

  public void createDriver(
      UUID company, UUID id, FleetRequests.DriverCreateRequest body, DriverStatus status) {
    db.update(
        "INSERT INTO drivers(id,company_id,name,department,status) VALUES(?,?,?,?,?)",
        id,
        company,
        body.name().strip(),
        body.department().strip(),
        status.name());
  }

  public void updateDriver(UUID company, UUID id, FleetRequests.DriverUpdateRequest body) {
    db.update(
        "UPDATE drivers SET name=?,department=?,status=? WHERE company_id=? AND id=?",
        body.name().strip(),
        body.department().strip(),
        body.status().name(),
        company,
        id);
  }

  public void activateDriver(UUID company, UUID id) {
    db.update("UPDATE drivers SET status='ACTIVE' WHERE company_id=? AND id=?", company, id);
  }

  public void updateConsent(
      UUID company, UUID driver, UUID vehicle, FleetRequests.ConsentRequest body) {
    db.update(
        "UPDATE drivers SET location_consent=? WHERE company_id=? AND id=?",
        body.granted(),
        company,
        driver);
    db.update(
        "INSERT INTO consent_events(id,company_id,driver_id,vehicle_id,granted,notice_version) VALUES(?,?,?,?,?,?)",
        UUID.randomUUID(),
        company,
        driver,
        vehicle,
        body.granted(),
        body.noticeVersion().strip());
  }

  public List<ConsentEvent> consents(UUID company, UUID driver) {
    return db.list(
        "SELECT * FROM consent_events WHERE company_id=? AND driver_id=? ORDER BY created_at,id",
        (r, n) ->
            new ConsentEvent(
                uuid(r, "id"),
                uuid(r, "company_id"),
                uuid(r, "driver_id"),
                uuid(r, "vehicle_id"),
                r.getBoolean("granted"),
                r.getString("notice_version"),
                instant(r, "created_at")),
        company,
        driver);
  }

  private RowMapper<CatalogItem> mapper(CatalogKind kind) {
    return kind == CatalogKind.CLIENTS ? CLIENT : PURPOSE;
  }

  public List<CatalogItem> catalog(UUID company, CatalogKind kind, boolean activeOnly) {
    return db.list(
        "SELECT * FROM "
            + kind.table()
            + " WHERE company_id=?"
            + (activeOnly ? " AND active=true" : "")
            + " ORDER BY name,id",
        mapper(kind),
        company);
  }

  public CatalogItem catalogItem(UUID company, CatalogKind kind, UUID id, boolean lock) {
    return db.one(
        "SELECT * FROM "
            + kind.table()
            + " WHERE company_id=? AND id=?"
            + (lock ? " FOR UPDATE" : ""),
        mapper(kind),
        company,
        id);
  }

  public CatalogItem shareCatalogItem(UUID company, CatalogKind kind, UUID id) {
    return db.one(
        "SELECT * FROM " + kind.table() + " WHERE company_id=? AND id=? FOR SHARE",
        mapper(kind),
        company,
        id);
  }

  public void createCatalog(
      UUID company, CatalogKind kind, UUID id, FleetRequests.CatalogRequest body) {
    if (kind == CatalogKind.CLIENTS)
      db.update(
          "INSERT INTO clients(id,company_id,name,address,description,active) VALUES(?,?,?,?,?,?)",
          id,
          company,
          body.name().strip(),
          body.address(),
          body.description(),
          body.active());
    else
      db.update(
          "INSERT INTO purposes(id,company_id,name,active) VALUES(?,?,?,?)",
          id,
          company,
          body.name().strip(),
          body.active());
  }

  public void updateCatalog(
      UUID company, CatalogKind kind, UUID id, FleetRequests.CatalogRequest body) {
    if (kind == CatalogKind.CLIENTS)
      db.update(
          "UPDATE clients SET name=?,address=?,description=?,active=? WHERE company_id=? AND id=?",
          body.name().strip(),
          body.address(),
          body.description(),
          body.active(),
          company,
          id);
    else
      db.update(
          "UPDATE purposes SET name=?,active=? WHERE company_id=? AND id=?",
          body.name().strip(),
          body.active(),
          company,
          id);
  }
}
