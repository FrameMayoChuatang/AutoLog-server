package io.autolog.trip;

import static io.autolog.fleet.FleetModels.*;
import static io.autolog.trip.TripAuditRepository.Operation.*;
import static io.autolog.trip.TripModels.*;
import static io.autolog.trip.TripRequests.*;

import io.autolog.auth.Actor;
import io.autolog.common.ApiException;
import io.autolog.fleet.FleetRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TripReviewService {
  private final TripRepository trips;
  private final FleetRepository fleet;
  private final TripAuditRepository audit;

  public TripReviewService(TripRepository trips, FleetRepository fleet, TripAuditRepository audit) {
    this.trips = trips;
    this.fleet = fleet;
    this.audit = audit;
  }

  private static String clean(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private String catalogText(
      Actor actor, CatalogKind kind, UUID id, String custom, UUID previousId, String previousText) {
    custom = clean(custom);
    if (id != null && custom != null) throw ApiException.bad("목록 ID와 직접 입력값은 동시에 지정할 수 없습니다.");
    if (id == null) return custom;
    if (id.equals(previousId) && previousText != null) return previousText;
    var item = fleet.shareCatalogItem(actor.companyId(), kind, id);
    if (!item.active()) throw ApiException.conflict("비활성 목록 항목은 새로 선택할 수 없습니다.");
    return item.name();
  }

  private void updateDetails(
      Actor actor, UUID id, DetailsRequest body, Trip before, boolean driverConfirm) {
    before.requireVersion(body.expectedVersion());
    boolean driverChanged = !Objects.equals(before.driverId(), body.driverId());
    var driver = fleet.shareDriver(actor.companyId(), body.driverId());
    if ((actor.isDevice() || driverChanged) && driver.status() != DriverStatus.ACTIVE)
      throw ApiException.conflict("관리자 승인을 받은 활성 운전자만 확인할 수 있습니다.");
    if (actor.isDevice() && before.driverId() != null && driverChanged)
      throw ApiException.conflict("등록된 운전자와 확인 운전자가 다릅니다.");
    if (driverConfirm && body.classification() == Classification.UNCONFIRMED)
      throw ApiException.bad("운행 유형을 선택해 주세요.");
    String purpose =
        catalogText(
            actor,
            CatalogKind.PURPOSES,
            body.purposeId(),
            body.purposeText(),
            before.purposeId(),
            before.purposeText());
    String client =
        catalogText(
            actor,
            CatalogKind.CLIENTS,
            body.clientId(),
            body.clientText(),
            before.clientId(),
            before.clientText());
    if (body.classification() == Classification.BUSINESS && purpose == null)
      throw ApiException.bad("업무 운행에는 목적이 필요합니다.");
    String driverName =
        !driverChanged && before.driverName() != null ? before.driverName() : driver.name();
    String department =
        !driverChanged && before.driverDepartment() != null
            ? before.driverDepartment()
            : driver.department();
    Instant confirmed = before.driverConfirmedAt();
    if (driverConfirm) confirmed = Instant.now();
    else if (driverChanged
        || actor.isDevice()
        || body.classification() == Classification.UNCONFIRMED) confirmed = null;
    Approval approval =
        confirmed != null && TripMeasurements.issues(before).isEmpty()
            ? Approval.PENDING
            : Approval.NOT_READY;
    trips.updateMetadata(
        actor,
        id,
        new TripRepository.Metadata(
            body,
            driverName,
            department,
            purpose,
            client,
            clean(body.note()),
            confirmed,
            approval));
  }

  @Transactional
  public TripResponse confirm(Actor actor, UUID id, DetailsRequest body) {
    actor.requireDevice();
    var before = trips.find(actor, id, true);
    if (!TripMeasurements.issues(before).isEmpty())
      throw ApiException.conflict("미완료 또는 측정값 오류가 있는 기록은 확인할 수 없습니다.");
    if (before.approvalStatus() == Approval.APPROVED)
      throw ApiException.conflict("승인된 기록은 관리자에게 수정 요청해 주세요.");
    if (before.measurementLockedAt() != null && clean(body.reason()) == null)
      throw ApiException.bad("이미 확인한 기록을 다시 확인·수정할 때는 사유를 입력해 주세요.");
    updateDetails(actor, id, body, before, true);
    return audit.record(actor, id, DRIVER_CONFIRM, body.reason(), before);
  }

  @Transactional
  public TripResponse editByDriver(Actor actor, UUID id, DetailsRequest body) {
    actor.requireDevice();
    var before = trips.find(actor, id, true);
    if (before.state() != State.COMPLETED) throw ApiException.conflict("종료된 운행만 분류를 편집할 수 있습니다.");
    if (before.approvalStatus() == Approval.APPROVED)
      throw ApiException.conflict("승인된 기록은 관리자에게 수정 요청해 주세요.");
    if (clean(body.reason()) == null) throw ApiException.bad("수정 사유를 입력해 주세요.");
    updateDetails(actor, id, body, before, false);
    return audit.record(actor, id, DRIVER_EDIT, body.reason().strip(), before);
  }

  @Transactional
  public TripResponse edit(Actor actor, UUID id, DetailsRequest body) {
    actor.requireAdmin();
    if (clean(body.reason()) == null) throw ApiException.bad("수정 사유를 입력해 주세요.");
    var before = trips.find(actor, id, true);
    updateDetails(actor, id, body, before, false);
    return audit.record(actor, id, ADMIN_EDIT, body.reason().strip(), before);
  }

  @Transactional
  public TripResponse approve(Actor actor, UUID id, ReviewRequest body) {
    actor.requireAdmin();
    var reference = trips.find(actor, id, false);
    fleet.vehicle(actor.companyId(), reference.vehicleId(), true);
    var before = trips.find(actor, id, true);
    before.requireVersion(body.expectedVersion());
    if (before.approvalStatus() != Approval.PENDING
        || before.driverConfirmedAt() == null
        || !TripMeasurements.issues(before).isEmpty())
      throw ApiException.conflict("운전자 확인을 마친 정상 기록만 승인할 수 있습니다.");
    if (trips.overlapsApproved(before))
      throw ApiException.conflict("같은 차량의 승인된 운행과 시간 또는 계기판 구간이 겹칩니다. 기존 기록을 검토해 주세요.");
    trips.approve(actor, id);
    return audit.record(actor, id, ADMIN_APPROVE, body.reason(), before);
  }

  @Transactional
  public TripResponse requestChanges(Actor actor, UUID id, ReviewRequest body) {
    actor.requireAdmin();
    if (clean(body.reason()) == null) throw ApiException.bad("보완 요청 사유를 입력해 주세요.");
    var before = trips.find(actor, id, true);
    before.requireVersion(body.expectedVersion());
    trips.requestChanges(actor, id);
    return audit.record(actor, id, REQUEST_CHANGES, body.reason().strip(), before);
  }
}
