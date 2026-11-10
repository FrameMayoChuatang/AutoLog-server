package io.autolog.trip;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class TripModels {
  private TripModels() {}

  public enum State {
    RUNNING,
    COMPLETED,
    INTERRUPTED
  }

  public enum Classification {
    UNCONFIRMED,
    COMMUTE,
    BUSINESS,
    PERSONAL
  }

  public enum Approval {
    NOT_READY,
    PENDING,
    APPROVED,
    CHANGES_REQUESTED
  }

  public record Sync(
      @Positive long sourceRevision,
      @PositiveOrZero long baseServerVersion,
      UUID driverId,
      @NotNull Instant startedAt,
      Instant endedAt,
      Instant lastObservedAt,
      @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal startOdometerKm,
      @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal endOdometerKm,
      @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal lastOdometerKm,
      @NotNull State state) {}

  public record Details(
      @Positive long expectedVersion,
      @NotNull UUID driverId,
      @NotNull Classification classification,
      UUID purposeId,
      @Size(max = 200) String purposeText,
      UUID clientId,
      @Size(max = 200) String clientText,
      @Size(max = 1000) String note,
      @Size(max = 1000) String reason) {}

  public record Review(@Positive long expectedVersion, @Size(max = 1000) String reason) {}
}
