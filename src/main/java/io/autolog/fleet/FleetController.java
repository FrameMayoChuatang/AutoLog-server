package io.autolog.fleet;

import static io.autolog.common.Db.*;

import io.autolog.auth.Actor;
import io.autolog.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class FleetController {
  private final Db db;

  public FleetController(Db db) {
    this.db = db;
  }

  public record Vehicle(
      @NotBlank @Size(max = 30) String registrationNumber,
      @NotBlank @Size(max = 100) String model,
      @NotNull Boolean active) {}

  public record Driver(
      @NotBlank @Size(max = 80) String name, @NotBlank @Size(max = 100) String department) {}

  public record DriverUpdate(
      @NotBlank @Size(max = 80) String name,
      @NotBlank @Size(max = 100) String department,
      @NotNull DriverStatus status) {}

  public enum DriverStatus {
    PENDING,
    ACTIVE,
    INACTIVE
  }

  public record Catalog(
      @NotBlank @Size(max = 120) String name,
      @Size(max = 300) String address,
      @NotNull Boolean active) {}

  public record Consent(@NotNull Boolean granted, @NotBlank @Size(max = 80) String noticeVersion) {}

  @GetMapping("/vehicles")
  public List<Map<String, Object>> vehicles(@AuthenticationPrincipal Actor actor) {
    return db.list(
        "SELECT * FROM vehicles WHERE company_id=? ORDER BY registration_number,id",
        actor.companyId());
  }

  @PostMapping("/vehicles")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> createVehicle(
      @AuthenticationPrincipal Actor actor, @Valid @RequestBody Vehicle body) {
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO vehicles(id,company_id,registration_number,model,active) VALUES(?,?,?,?,?)",
        id,
        actor.companyId(),
        body.registrationNumber().strip(),
        body.model().strip(),
        body.active());
    return vehicle(actor, id);
  }

  private Map<String, Object> vehicle(Actor actor, UUID id) {
    return db.one("SELECT * FROM vehicles WHERE company_id=? AND id=?", actor.companyId(), id);
  }

  @GetMapping("/vehicles/{id}")
  public Map<String, Object> getVehicle(
      @AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
    return vehicle(actor, id);
  }

  @PatchMapping("/vehicles/{id}")
  @Transactional
  public Map<String, Object> updateVehicle(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID id,
      @Valid @RequestBody Vehicle body) {
    db.one("SELECT id FROM vehicles WHERE company_id=? AND id=? FOR UPDATE", actor.companyId(), id);
    db.update(
        "UPDATE vehicles SET registration_number=?,model=?,active=? WHERE company_id=? AND id=?",
        body.registrationNumber().strip(),
        body.model().strip(),
        body.active(),
        actor.companyId(),
        id);
    if (!body.active())
      db.update(
          "UPDATE credentials SET revoked_at=now() WHERE company_id=? AND vehicle_id=? AND revoked_at IS NULL",
          actor.companyId(),
          id);
    return vehicle(actor, id);
  }

  @GetMapping("/device/vehicle")
  public Map<String, Object> deviceVehicle(@AuthenticationPrincipal Actor actor) {
    return vehicle(actor, actor.vehicleId());
  }

  @GetMapping({"/drivers", "/device/drivers"})
  public List<Map<String, Object>> drivers(
      @AuthenticationPrincipal Actor actor,
      @RequestParam(defaultValue = "") @Size(max = 80) String q) {
    if (q.length() > 80) throw ApiException.bad("검색어가 너무 깁니다.");
    return db.list(
        "SELECT * FROM drivers WHERE company_id=? AND (name ILIKE ? OR department ILIKE ?)"
            + (actor.isDevice() ? " AND status<>'INACTIVE'" : "")
            + " ORDER BY name,department,id",
        actor.companyId(),
        "%" + q + "%",
        "%" + q + "%");
  }

  @PostMapping({"/drivers", "/device/drivers"})
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> createDriver(
      @AuthenticationPrincipal Actor actor, @Valid @RequestBody Driver body) {
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO drivers(id,company_id,name,department,status) VALUES(?,?,?,?,?)",
        id,
        actor.companyId(),
        body.name().strip(),
        body.department().strip(),
        actor.isDevice() ? "PENDING" : "ACTIVE");
    return driver(actor, id);
  }

  private Map<String, Object> driver(Actor actor, UUID id) {
    return db.one("SELECT * FROM drivers WHERE company_id=? AND id=?", actor.companyId(), id);
  }

  @PatchMapping("/drivers/{id}")
  @Transactional
  public Map<String, Object> updateDriver(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID id,
      @Valid @RequestBody DriverUpdate body) {
    db.one("SELECT id FROM drivers WHERE company_id=? AND id=? FOR UPDATE", actor.companyId(), id);
    db.update(
        "UPDATE drivers SET name=?,department=?,status=? WHERE company_id=? AND id=?",
        body.name().strip(),
        body.department().strip(),
        body.status().name(),
        actor.companyId(),
        id);
    return driver(actor, id);
  }

  @PostMapping("/drivers/{id}/approve")
  @Transactional
  public Map<String, Object> approveDriver(
      @AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
    var driver =
        db.one(
            "SELECT * FROM drivers WHERE company_id=? AND id=? FOR UPDATE", actor.companyId(), id);
    if (!"PENDING".equals(text(driver, "status")))
      throw ApiException.conflict("등록 대기 운전자만 승인할 수 있습니다.");
    db.update(
        "UPDATE drivers SET status='ACTIVE' WHERE company_id=? AND id=?", actor.companyId(), id);
    return driver(actor, id);
  }

  @PostMapping("/device/drivers/{id}/consent")
  @Transactional
  public Map<String, Object> consent(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID id,
      @Valid @RequestBody Consent body) {
    var driver =
        db.one(
            "SELECT * FROM drivers WHERE company_id=? AND id=? FOR UPDATE", actor.companyId(), id);
    if ("INACTIVE".equals(text(driver, "status"))) throw ApiException.conflict("비활성 운전자입니다.");
    db.update(
        "UPDATE drivers SET location_consent=? WHERE company_id=? AND id=?",
        body.granted(),
        actor.companyId(),
        id);
    db.update(
        "INSERT INTO consent_events(id,company_id,driver_id,vehicle_id,granted,notice_version) VALUES(?,?,?,?,?,?)",
        UUID.randomUUID(),
        actor.companyId(),
        id,
        actor.vehicleId(),
        body.granted(),
        body.noticeVersion().strip());
    return driver(actor, id);
  }

  @GetMapping("/drivers/{id}/consents")
  public List<Map<String, Object>> consents(
      @AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
    driver(actor, id);
    return db.list(
        "SELECT * FROM consent_events WHERE company_id=? AND driver_id=? ORDER BY created_at,id",
        actor.companyId(),
        id);
  }

  private String table(String kind) {
    // Path input never becomes an SQL identifier without this fixed allowlist.
    return switch (kind) {
      case "clients" -> "clients";
      case "purposes" -> "purposes";
      default -> throw ApiException.missing();
    };
  }

  @GetMapping({"/catalog/{kind}", "/device/catalog/{kind}"})
  public List<Map<String, Object>> catalog(
      @AuthenticationPrincipal Actor actor, @PathVariable String kind) {
    return db.list(
        "SELECT * FROM "
            + table(kind)
            + " WHERE company_id=?"
            + (actor.isDevice() ? " AND active=true" : "")
            + " ORDER BY name,id",
        actor.companyId());
  }

  @PostMapping("/catalog/{kind}")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> createCatalog(
      @AuthenticationPrincipal Actor actor,
      @PathVariable String kind,
      @Valid @RequestBody Catalog body) {
    String table = table(kind);
    UUID id = UUID.randomUUID();
    if (table.equals("clients"))
      db.update(
          "INSERT INTO clients(id,company_id,name,address,active) VALUES(?,?,?,?,?)",
          id,
          actor.companyId(),
          body.name().strip(),
          body.address(),
          body.active());
    else {
      if (body.address() != null) throw ApiException.bad("목적에는 주소를 지정할 수 없습니다.");
      db.update(
          "INSERT INTO purposes(id,company_id,name,active) VALUES(?,?,?,?)",
          id,
          actor.companyId(),
          body.name().strip(),
          body.active());
    }
    return db.one("SELECT * FROM " + table + " WHERE company_id=? AND id=?", actor.companyId(), id);
  }

  @PatchMapping("/catalog/{kind}/{id}")
  @Transactional
  public Map<String, Object> updateCatalog(
      @AuthenticationPrincipal Actor actor,
      @PathVariable String kind,
      @PathVariable UUID id,
      @Valid @RequestBody Catalog body) {
    String table = table(kind);
    db.one(
        "SELECT id FROM " + table + " WHERE company_id=? AND id=? FOR UPDATE",
        actor.companyId(),
        id);
    if (table.equals("clients"))
      db.update(
          "UPDATE clients SET name=?,address=?,active=? WHERE company_id=? AND id=?",
          body.name().strip(),
          body.address(),
          body.active(),
          actor.companyId(),
          id);
    else {
      if (body.address() != null) throw ApiException.bad("목적에는 주소를 지정할 수 없습니다.");
      db.update(
          "UPDATE purposes SET name=?,active=? WHERE company_id=? AND id=?",
          body.name().strip(),
          body.active(),
          actor.companyId(),
          id);
    }
    return db.one("SELECT * FROM " + table + " WHERE company_id=? AND id=?", actor.companyId(), id);
  }
}
