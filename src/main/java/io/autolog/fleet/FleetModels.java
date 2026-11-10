package io.autolog.fleet;

import io.autolog.common.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public final class FleetModels {
  private FleetModels() {}

  public enum DriverStatus {
    PENDING,
    ACTIVE,
    INACTIVE
  }

  public enum CatalogKind {
    CLIENTS("clients"),
    PURPOSES("purposes");
    private final String table;

    CatalogKind(String table) {
      this.table = table;
    }

    public String table() {
      return table;
    }

    public static CatalogKind from(String value) {
      for (var kind : values()) if (kind.table.equals(value)) return kind;
      throw ApiException.missing();
    }
  }

  public record Vehicle(
      UUID id,
      UUID companyId,
      String registrationNumber,
      String model,
      boolean active,
      Instant createdAt) {}

  public record Driver(
      UUID id,
      UUID companyId,
      String name,
      String department,
      DriverStatus status,
      boolean locationConsent,
      Instant createdAt) {}

  @Schema(anyOf = {Client.class, Purpose.class})
  public sealed interface CatalogItem permits Client, Purpose {
    UUID id();

    String name();

    boolean active();
  }

  public record Client(
      UUID id,
      UUID companyId,
      String name,
      String address,
      String description,
      boolean active,
      Instant createdAt)
      implements CatalogItem {}

  public record Purpose(UUID id, UUID companyId, String name, boolean active, Instant createdAt)
      implements CatalogItem {}

  public record ConsentEvent(
      UUID id,
      UUID companyId,
      UUID driverId,
      UUID vehicleId,
      boolean granted,
      String noticeVersion,
      Instant createdAt) {}
}
