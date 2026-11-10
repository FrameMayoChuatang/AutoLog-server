package io.autolog.trip;

import static io.autolog.trip.TripModels.*;

import io.autolog.common.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public record Trip(
    UUID id,
    UUID companyId,
    UUID vehicleId,
    UUID driverId,
    Instant startedAt,
    Instant endedAt,
    Instant lastObservedAt,
    BigDecimal startOdometerKm,
    BigDecimal endOdometerKm,
    BigDecimal lastOdometerKm,
    State state,
    Classification classification,
    UUID purposeId,
    UUID clientId,
    String purposeText,
    String clientText,
    String note,
    String driverName,
    String driverDepartment,
    Instant driverConfirmedAt,
    Approval approvalStatus,
    UUID approvedBy,
    Instant approvedAt,
    long sourceRevision,
    String sourcePayloadHash,
    long serverVersion,
    Instant createdAt,
    Instant updatedAt,
    Instant measurementLockedAt,
    MeasurementSource measurementSource,
    List<CollectionIssue> collectionIssues) {
  public void requireVersion(long expected) {
    if (serverVersion != expected)
      throw ApiException.conflict("다른 변경이 먼저 반영되었습니다. 최신 serverVersion으로 다시 요청하세요.");
  }
}
