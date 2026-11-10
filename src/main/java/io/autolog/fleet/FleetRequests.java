package io.autolog.fleet;

import io.autolog.fleet.FleetModels.DriverStatus;
import jakarta.validation.constraints.*;

public final class FleetRequests {
  private FleetRequests() {}

  public record VehicleRequest(
      @NotBlank @Size(max = 30) String registrationNumber,
      @NotBlank @Size(max = 100) String model,
      @NotNull Boolean active) {}

  public record DriverCreateRequest(
      @NotBlank @Size(max = 80) String name, @NotBlank @Size(max = 100) String department) {}

  public record DriverUpdateRequest(
      @NotBlank @Size(max = 80) String name,
      @NotBlank @Size(max = 100) String department,
      @NotNull DriverStatus status) {}

  public record CatalogRequest(
      @NotBlank @Size(max = 120) String name,
      @Size(max = 300) String address,
      @Size(max = 500) String description,
      @NotNull Boolean active) {}

  public record ConsentRequest(
      @NotNull Boolean granted, @NotBlank @Size(max = 80) String noticeVersion) {}
}
