package io.autolog.auth;

import jakarta.validation.constraints.*;

public final class AuthRequests {
  private AuthRequests() {}

  public record SignupRequest(
      @NotBlank @Size(max = 120) String companyName,
      @NotBlank @Size(max = 80) String ownerName,
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank @Size(min = 10, max = 72) String password) {}

  public record LoginRequest(
      @NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 72) String password) {}

  public record PasswordSetupRequest(
      @NotBlank @Size(max = 128) String token,
      @NotBlank @Size(min = 10, max = 72) String password) {}

  public record StaffCreateRequest(
      @NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 80) String name) {}

  public record ActiveStatusRequest(@NotNull Boolean active) {}

  public record PairingRequest(@NotBlank @Size(max = 128) String pairingToken) {}
}
