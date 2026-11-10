package io.autolog.trip;

import static io.autolog.trip.TripModels.*;
import static io.autolog.trip.TripRequests.*;

import io.autolog.common.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public final class TripSyncPolicy {
  private TripSyncPolicy() {}

  public static void validate(SyncRequest body) {
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

  public static void validateProgress(Trip before, SyncRequest body) {
    if (body.sourceRevision() <= before.sourceRevision())
      throw ApiException.conflict("이미 처리한 revision입니다. 서버 기록을 다시 조회하세요.");
    before.requireVersion(body.baseServerVersion());
    if (before.measurementLockedAt() != null)
      throw ApiException.conflict("확인된 기록의 원본 측정값은 덮어쓸 수 없습니다.");
    if (!body.startedAt().equals(before.startedAt()))
      throw ApiException.conflict("운행 시작 시각은 변경할 수 없습니다.");
    if (before.startOdometerKm() != null
        && before.startOdometerKm().signum() > 0
        && (body.startOdometerKm() == null
            || before.startOdometerKm().compareTo(body.startOdometerKm()) != 0))
      throw ApiException.conflict("이미 저장한 시작 계기판 값은 변경할 수 없습니다.");
    if (before.driverId() != null && !Objects.equals(before.driverId(), body.driverId()))
      throw ApiException.conflict("운전자 변경은 새 운행 구간으로 기록해야 합니다.");
    if (before.state() == State.COMPLETED && body.state() != State.COMPLETED)
      throw ApiException.conflict("완료된 운행을 진행 중 상태로 되돌릴 수 없습니다.");
    Instant previousObserved = before.lastObservedAt();
    BigDecimal previousLast = before.lastOdometerKm();
    if (previousLast != null
        && body.lastOdometerKm() == null
        && body.lastObservedAt() != null
        && !body.lastObservedAt().equals(previousObserved))
      throw ApiException.bad("저장된 관측값의 시각만 변경할 수 없습니다. 누적거리와 관측 시각을 함께 전송하세요.");
    if (previousObserved != null
        && body.lastObservedAt() != null
        && body.lastObservedAt().isBefore(previousObserved))
      throw ApiException.conflict("마지막 관측 시각을 이전으로 되돌릴 수 없습니다.");
    if (previousObserved != null
        && body.endedAt() != null
        && body.endedAt().isBefore(previousObserved))
      throw ApiException.bad("종료 시각이 저장된 마지막 관측보다 빠릅니다.");
    if (previousLast != null
        && previousLast.signum() > 0
        && body.lastOdometerKm() != null
        && body.lastOdometerKm().compareTo(previousLast) < 0)
      throw ApiException.conflict("유효한 마지막 누적거리를 더 작은 관측값으로 덮어쓸 수 없습니다.");
  }
}
