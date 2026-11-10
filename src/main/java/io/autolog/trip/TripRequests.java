package io.autolog.trip;

import static io.autolog.trip.TripModels.*;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public final class TripRequests {
  private TripRequests() {}

  public record SyncRequest(
      @Positive long sourceRevision,
      @PositiveOrZero long baseServerVersion,
      UUID driverId,
      @NotNull Instant startedAt,
      Instant endedAt,
      Instant lastObservedAt,
      @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal startOdometerKm,
      @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal endOdometerKm,
      @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal lastOdometerKm,
      @NotNull State state,
      MeasurementSource measurementSource,
      @Size(max = 3) Set<@NotNull CollectionIssue> collectionIssues) {}

  public record DetailsRequest(
      @Positive long expectedVersion,
      @NotNull UUID driverId,
      @NotNull Classification classification,
      UUID purposeId,
      @Size(max = 200) String purposeText,
      UUID clientId,
      @Size(max = 200) String clientText,
      @Size(max = 1000) String note,
      @Size(max = 1000) String reason) {}

  public record ReviewRequest(@Positive long expectedVersion, @Size(max = 1000) String reason) {}
}
