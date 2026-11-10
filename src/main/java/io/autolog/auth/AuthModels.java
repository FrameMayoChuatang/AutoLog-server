package io.autolog.auth;

import io.autolog.fleet.FleetModels.Vehicle;
import java.time.Instant;
import java.util.UUID;

public final class AuthModels {
  private AuthModels() {}

  public enum Role {
    OWNER,
    STAFF
  }

  public enum CredentialKind {
    ADMIN,
    SETUP,
    DEVICE,
    PAIRING
  }

  public sealed interface CurrentUser permits AdminProfile, VehicleIdentity {}

  public record AdminProfile(
      UUID id,
      UUID companyId,
      String email,
      String name,
      Role role,
      boolean active,
      Instant createdAt)
      implements CurrentUser {}

  public record VehicleIdentity(
      UUID id, UUID companyId, String registrationNumber, String model, boolean active)
      implements CurrentUser {
    public static VehicleIdentity from(Vehicle value) {
      return new VehicleIdentity(
          value.id(), value.companyId(), value.registrationNumber(), value.model(), value.active());
    }
  }

  public record AdminAccount(
      UUID id,
      UUID companyId,
      String email,
      String name,
      String passwordHash,
      Role role,
      boolean active,
      Instant createdAt) {
    public AdminProfile profile() {
      return new AdminProfile(id, companyId, email, name, role, active, createdAt);
    }
  }

  public record AdminSummary(
      UUID id,
      String email,
      String name,
      Role role,
      boolean active,
      boolean passwordConfigured,
      Instant createdAt) {}

  public record Credential(UUID id, UUID companyId, UUID adminId, UUID vehicleId) {}

  public record IssuedToken(String token, Instant expiresAt) {}

  public record SetupLink(String setupToken, String setupLink, Instant expiresAt) {}

  public record Session(
      String accessToken, String tokenType, Instant expiresAt, AdminProfile admin) {}

  public record StaffInvitation(AdminProfile admin, SetupLink invitation) {}

  public record DeviceSession(
      String accessToken, String tokenType, Instant expiresAt, Vehicle vehicle) {}
}
