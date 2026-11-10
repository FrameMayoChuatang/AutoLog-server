package io.autolog.trip;

import static io.autolog.common.Db.*;
import static io.autolog.trip.TripModels.*;

import io.autolog.auth.*;
import io.autolog.common.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TripService {
  private final Db db;

  public TripService(Db db) {
    this.db = db;
  }

  private Map<String, Object> raw(Actor actor, UUID id, boolean lock) {
    List<Object> args = new ArrayList<>(List.of(actor.companyId(), id));
    String sql = "SELECT * FROM trips WHERE company_id=? AND id=?";
    if (actor.isDevice()) {
      sql += " AND vehicle_id=?";
      args.add(actor.vehicleId());
    }
    return db.one(sql + (lock ? " FOR UPDATE" : ""), args.toArray());
  }

  public Map<String, Object> get(Actor actor, UUID id) {
    return decorate(raw(actor, id, false));
  }

  public static List<String> issues(Map<String, Object> trip) {
    List<String> issues = new ArrayList<>();
    if (!"COMPLETED".equals(text(trip, "state"))) issues.add("INCOMPLETE");
    BigDecimal start = decimal(trip, "startOdometerKm"), end = decimal(trip, "endOdometerKm");
    if (start == null) issues.add("MISSING_START_ODOMETER");
    else if (start.signum() == 0) issues.add("ZERO_START_ODOMETER");
    if (end == null) issues.add("MISSING_END_ODOMETER");
    if (start != null && end != null && end.compareTo(start) < 0) issues.add("ODOMETER_REGRESSION");
    if (start != null
        && end != null
        && end.compareTo(start) > 0
        && Objects.equals(trip.get("startedAt"), trip.get("endedAt")))
      issues.add("ZERO_DURATION_WITH_DISTANCE");
    return issues;
  }

  public static Map<String, Object> decorate(Map<String, Object> trip) {
    var result = new LinkedHashMap<>(trip);
    result.remove("sourcePayloadHash");
    var issues = issues(trip);
    result.put("qualityIssues", issues);
    result.put(
        "distanceKm",
        issues.isEmpty()
            ? decimal(trip, "endOdometerKm").subtract(decimal(trip, "startOdometerKm"))
            : null);
    BigDecimal start = decimal(trip, "startOdometerKm"), last = decimal(trip, "lastOdometerKm");
    result.put(
        "observedDistanceKm",
        start != null && start.signum() > 0 && last != null && last.compareTo(start) >= 0
            ? last.subtract(start)
            : null);
    result.put(
        "reportEligible",
        issues.isEmpty()
            && "APPROVED".equals(text(trip, "approvalStatus"))
            && trip.get("driverConfirmedAt") != null);
    return result;
  }

  private void validateSync(Sync body) {
    for (Instant time : new Instant[] {body.startedAt(), body.endedAt(), body.lastObservedAt()})
      if (time != null && time.getNano() % 1000 != 0)
        throw ApiException.bad("시각은 마이크로초 이하 정밀도로 전송해 주세요.");
    if (body.startedAt().isAfter(Instant.now().plusSeconds(300)))
      throw ApiException.bad("운행 시작 시각이 미래입니다.");
    if (body.endedAt() != null
        && (body.endedAt().isBefore(body.startedAt())
            || body.endedAt().isAfter(Instant.now().plusSeconds(300))))
      throw ApiException.bad("종료 시각이 유효하지 않습니다.");
    if (body.lastObservedAt() != null
        && (body.lastObservedAt().isBefore(body.startedAt())
            || body.lastObservedAt().isAfter(Instant.now().plusSeconds(300))
            || (body.endedAt() != null && body.lastObservedAt().isAfter(body.endedAt()))))
      throw ApiException.bad("마지막 관측 시각이 유효하지 않습니다.");
    if (body.lastOdometerKm() != null && body.lastObservedAt() == null)
      throw ApiException.bad("관측값에는 관측 시각이 필요합니다.");
    if (body.state() == State.COMPLETED && body.endedAt() == null)
      throw ApiException.bad("완료 운행에는 종료 시각이 필요합니다.");
    if (body.state() == State.RUNNING && (body.endedAt() != null || body.endOdometerKm() != null))
      throw ApiException.bad("진행 중 운행에는 종료값을 지정할 수 없습니다.");
  }

  @Transactional
  public Map<String, Object> sync(Actor actor, UUID id, Sync body) {
    actor.requireDevice();
    validateSync(body);
    // The vehicle lock serializes first uploads and duplicate concurrent requests.
    var vehicle =
        db.one(
            "SELECT active FROM vehicles WHERE company_id=? AND id=? FOR UPDATE",
            actor.companyId(),
            actor.vehicleId());
    if (!Boolean.TRUE.equals(vehicle.get("active"))) throw ApiException.denied();
    var existing =
        db.optional(
            "SELECT * FROM trips WHERE company_id=? AND vehicle_id=? AND id=? FOR UPDATE",
            actor.companyId(),
            actor.vehicleId(),
            id);
    String hash = TokenCodec.hash(db.json(body));
    if (existing.isPresent()) {
      var before = existing.get();
      long revision = number(before, "sourceRevision");
      if (body.sourceRevision() == revision && hash.equals(text(before, "sourcePayloadHash"))) {
        var replay = decorate(before);
        replay.put("replayed", true);
        return replay;
      }
      if (body.sourceRevision() <= revision)
        throw ApiException.conflict("이미 처리한 revision입니다. 서버 기록을 다시 조회하세요.");
      version(before, body.baseServerVersion());
      if (before.get("driverConfirmedAt") != null)
        throw ApiException.conflict("확인된 기록의 원본 측정값은 덮어쓸 수 없습니다.");
      if (!body.startedAt().equals(before.get("startedAt")))
        throw ApiException.conflict("운행 시작 시각은 변경할 수 없습니다.");
      if (decimal(before, "startOdometerKm") != null
          && decimal(before, "startOdometerKm").signum() > 0
          && (body.startOdometerKm() == null
              || decimal(before, "startOdometerKm").compareTo(body.startOdometerKm()) != 0))
        throw ApiException.conflict("이미 저장한 시작 계기판 값은 변경할 수 없습니다.");
      if (before.get("driverId") != null
          && !Objects.equals(before.get("driverId"), body.driverId()))
        throw ApiException.conflict("운전자 변경은 새 운행 구간으로 기록해야 합니다.");
      if ("COMPLETED".equals(text(before, "state")) && body.state() != State.COMPLETED)
        throw ApiException.conflict("완료된 운행을 진행 중 상태로 되돌릴 수 없습니다.");
      validateDriver(actor, body.driverId(), false);
      db.update(
          "UPDATE trips SET driver_id=?,ended_at=?,last_observed_at=?,start_odometer_km=?,end_odometer_km=?,last_odometer_km=?,state=?,source_revision=?,source_payload_hash=?,server_version=server_version+1,updated_at=now() WHERE company_id=? AND id=?",
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
      return audited(actor, id, "DEVICE_SYNC", null, before);
    }
    if (body.baseServerVersion() != 0)
      throw ApiException.conflict("신규 운행의 baseServerVersion은 0이어야 합니다.");
    validateDriver(actor, body.driverId(), false);
    db.update(
        "INSERT INTO trips(id,company_id,vehicle_id,driver_id,started_at,ended_at,last_observed_at,start_odometer_km,end_odometer_km,last_odometer_km,state,source_revision,source_payload_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
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
    return audited(actor, id, "DEVICE_SYNC", null, null);
  }

  private Map<String, Object> validateDriver(Actor actor, UUID driver, boolean activeRequired) {
    if (driver == null) return null;
    var row =
        db.one(
            "SELECT * FROM drivers WHERE company_id=? AND id=? FOR SHARE",
            actor.companyId(),
            driver);
    if (activeRequired && !"ACTIVE".equals(text(row, "status")))
      throw ApiException.conflict("관리자 승인을 받은 활성 운전자만 확인할 수 있습니다.");
    // Historical offline measurements may still refer to a driver deactivated after the drive.
    return row;
  }

  private static String clean(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private String catalogText(
      Actor actor, String table, UUID id, String custom, Object previousId, String previousText) {
    custom = clean(custom);
    if (id != null && custom != null) throw ApiException.bad("목록 ID와 직접 입력값은 동시에 지정할 수 없습니다.");
    if (id == null) return custom;
    if (id.equals(previousId) && previousText != null) return previousText;
    var row =
        db.one(
            "SELECT name,active FROM " + table + " WHERE company_id=? AND id=? FOR SHARE",
            actor.companyId(),
            id);
    if (!Boolean.TRUE.equals(row.get("active")))
      throw ApiException.conflict("비활성 목록 항목은 새로 선택할 수 없습니다.");
    return text(row, "name");
  }

  private void details(
      Actor actor, UUID id, Details body, Map<String, Object> before, boolean driverConfirm) {
    version(before, body.expectedVersion());
    var driver = validateDriver(actor, body.driverId(), true);
    if (driverConfirm
        && before.get("driverId") != null
        && !Objects.equals(before.get("driverId"), body.driverId()))
      throw ApiException.conflict("등록된 운전자와 확인 운전자가 다릅니다.");
    if (body.classification() == Classification.UNCONFIRMED)
      throw ApiException.bad("운행 유형을 선택해 주세요.");
    String purpose =
        catalogText(
            actor,
            "purposes",
            body.purposeId(),
            body.purposeText(),
            before.get("purposeId"),
            text(before, "purposeText"));
    String client =
        catalogText(
            actor,
            "clients",
            body.clientId(),
            body.clientText(),
            before.get("clientId"),
            text(before, "clientText"));
    if (body.classification() == Classification.BUSINESS && purpose == null)
      throw ApiException.bad("업무 운행에는 목적이 필요합니다.");
    boolean driverChanged = !Objects.equals(before.get("driverId"), body.driverId());
    String driverName =
        !driverChanged && before.get("driverName") != null
            ? text(before, "driverName")
            : text(driver, "name");
    String department =
        !driverChanged && before.get("driverDepartment") != null
            ? text(before, "driverDepartment")
            : text(driver, "department");
    Object confirmed =
        driverConfirm ? Instant.now() : (driverChanged ? null : before.get("driverConfirmedAt"));
    String approval = confirmed != null && issues(before).isEmpty() ? "PENDING" : "NOT_READY";
    db.update(
        "UPDATE trips SET driver_id=?,driver_name=?,driver_department=?,classification=?,purpose_id=?,purpose_text=?,client_id=?,client_text=?,note=?,driver_confirmed_at=?,approval_status=?,approved_by=NULL,approved_at=NULL,server_version=server_version+1,updated_at=now() WHERE company_id=? AND id=?",
        body.driverId(),
        driverName,
        department,
        body.classification().name(),
        body.purposeId(),
        purpose,
        body.clientId(),
        client,
        clean(body.note()),
        confirmed,
        approval,
        actor.companyId(),
        id);
  }

  @Transactional
  public Map<String, Object> confirm(Actor actor, UUID id, Details body) {
    actor.requireDevice();
    var before = raw(actor, id, true);
    if (!issues(before).isEmpty()) throw ApiException.conflict("미완료 또는 측정값 오류가 있는 기록은 확인할 수 없습니다.");
    if ("APPROVED".equals(text(before, "approvalStatus")))
      throw ApiException.conflict("승인된 기록은 관리자에게 수정 요청해 주세요.");
    details(actor, id, body, before, true);
    return audited(actor, id, "DRIVER_CONFIRM", body.reason(), before);
  }

  @Transactional
  public Map<String, Object> edit(Actor actor, UUID id, Details body) {
    actor.requireAdmin();
    if (clean(body.reason()) == null) throw ApiException.bad("수정 사유를 입력해 주세요.");
    var before = raw(actor, id, true);
    details(actor, id, body, before, false);
    return audited(actor, id, "ADMIN_EDIT", body.reason().strip(), before);
  }

  @Transactional
  public Map<String, Object> approve(Actor actor, UUID id, Review body) {
    actor.requireAdmin();
    var reference = raw(actor, id, false);
    db.one(
        "SELECT id FROM vehicles WHERE company_id=? AND id=? FOR UPDATE",
        actor.companyId(),
        uuid(reference, "vehicleId"));
    var before = raw(actor, id, true);
    version(before, body.expectedVersion());
    if (!"PENDING".equals(text(before, "approvalStatus"))
        || before.get("driverConfirmedAt") == null
        || !issues(before).isEmpty()) throw ApiException.conflict("운전자 확인을 마친 정상 기록만 승인할 수 있습니다.");
    if (!db.list(
            "SELECT id FROM trips WHERE company_id=? AND vehicle_id=? AND id<>? AND approval_status='APPROVED' AND ((started_at<? AND ended_at>?) OR (start_odometer_km<? AND end_odometer_km>?)) LIMIT 1",
            actor.companyId(),
            uuid(before, "vehicleId"),
            id,
            before.get("endedAt"),
            before.get("startedAt"),
            before.get("endOdometerKm"),
            before.get("startOdometerKm"))
        .isEmpty())
      throw ApiException.conflict("같은 차량의 승인된 운행과 시간 또는 계기판 구간이 겹칩니다. 기존 기록을 검토해 주세요.");
    db.update(
        "UPDATE trips SET approval_status='APPROVED',approved_by=?,approved_at=now(),server_version=server_version+1,updated_at=now() WHERE company_id=? AND id=?",
        actor.adminId(),
        actor.companyId(),
        id);
    return audited(actor, id, "ADMIN_APPROVE", body.reason(), before);
  }

  @Transactional
  public Map<String, Object> requestChanges(Actor actor, UUID id, Review body) {
    actor.requireAdmin();
    if (clean(body.reason()) == null) throw ApiException.bad("보완 요청 사유를 입력해 주세요.");
    var before = raw(actor, id, true);
    version(before, body.expectedVersion());
    db.update(
        "UPDATE trips SET approval_status='CHANGES_REQUESTED',driver_confirmed_at=NULL,approved_by=NULL,approved_at=NULL,server_version=server_version+1,updated_at=now() WHERE company_id=? AND id=?",
        actor.companyId(),
        id);
    return audited(actor, id, "REQUEST_CHANGES", body.reason().strip(), before);
  }

  private static void version(Map<String, Object> trip, long expected) {
    if (number(trip, "serverVersion") != expected)
      throw ApiException.conflict("다른 변경이 먼저 반영되었습니다. 최신 serverVersion으로 다시 요청하세요.");
  }

  private Map<String, Object> audited(
      Actor actor, UUID id, String operation, String reason, Map<String, Object> before) {
    var after = raw(actor, id, false);
    db.update(
        "INSERT INTO trip_audit(id,company_id,trip_id,server_version,actor_admin_id,actor_vehicle_id,operation,reason,before_value,after_value) VALUES(?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)",
        UUID.randomUUID(),
        actor.companyId(),
        id,
        number(after, "serverVersion"),
        actor.adminId(),
        actor.vehicleId(),
        operation,
        reason,
        before == null ? null : db.json(decorate(before)),
        db.json(decorate(after)));
    return decorate(after);
  }

  public List<Map<String, Object>> audit(Actor actor, UUID id) {
    raw(actor, id, false);
    return db.list(
        "SELECT id,server_version,actor_admin_id,actor_vehicle_id,operation,reason,before_value,after_value,created_at FROM trip_audit WHERE company_id=? AND trip_id=? ORDER BY server_version",
        actor.companyId(),
        id);
  }
}
