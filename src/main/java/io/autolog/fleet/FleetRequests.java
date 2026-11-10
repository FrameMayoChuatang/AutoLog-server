package io.autolog.fleet;

import io.autolog.fleet.FleetModels.DriverStatus;
import jakarta.validation.constraints.*;

public final class FleetRequests {
  private FleetRequests() {}

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

  public record Catalog(
      @NotBlank @Size(max = 120) String name,
      @Size(max = 300) String address,
      @Size(max = 500) String description,
      @NotNull Boolean active) {}

  public record Consent(@NotNull Boolean granted, @NotBlank @Size(max = 80) String noticeVersion) {}
}
