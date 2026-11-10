package io.autolog.auth;

import static io.autolog.auth.AuthModels.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AuthController {
  private final AuthService service;

  public AuthController(AuthService service) {
    this.service = service;
  }

  public record Signup(
      @NotBlank @Size(max = 120) String companyName,
      @NotBlank @Size(max = 80) String ownerName,
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank @Size(min = 10, max = 72) String password) {}

  public record Login(
      @NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 72) String password) {}

  public record Setup(
      @NotBlank @Size(max = 128) String token,
      @NotBlank @Size(min = 10, max = 72) String password) {}

  public record Staff(
      @NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 80) String name) {}

  public record Active(@NotNull Boolean active) {}

  public record Pair(@NotBlank @Size(max = 128) String pairingToken) {}

  @PostMapping("/auth/companies")
  @ResponseStatus(HttpStatus.CREATED)
  public Session signup(@Valid @RequestBody Signup body) {
    return service.signup(body.companyName(), body.ownerName(), body.email(), body.password());
  }

  @PostMapping("/auth/login")
  public Session login(@Valid @RequestBody Login body) {
    return service.login(body.email(), body.password());
  }

  @PostMapping("/auth/password/setup")
  public Session setup(@Valid @RequestBody Setup body) {
    return service.finishSetup(body.token(), body.password());
  }

  @GetMapping("/auth/me")
  public CurrentUser me(@AuthenticationPrincipal Actor actor) {
    return service.me(actor);
  }

  @PostMapping("/auth/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(@AuthenticationPrincipal Actor actor) {
    service.logout(actor);
  }

  @GetMapping("/admins")
  public List<AdminSummary> admins(@AuthenticationPrincipal Actor actor) {
    return service.admins(actor);
  }

  @PostMapping("/admins")
  @ResponseStatus(HttpStatus.CREATED)
  public StaffInvitation createStaff(
      @AuthenticationPrincipal Actor actor, @Valid @RequestBody Staff body) {
    return service.createStaff(actor, body.email(), body.name());
  }

  @PostMapping("/admins/{id}/setup-link")
  public SetupLink setupLink(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
    return service.reissueSetup(actor, id);
  }

  @PatchMapping("/admins/{id}/active")
  public AdminProfile active(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID id,
      @Valid @RequestBody Active body) {
    return service.setStaffActive(actor, id, body.active());
  }

  @PostMapping("/vehicles/{id}/pairing")
  public IssuedToken pairing(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
    return service.pairingCode(actor, id);
  }

  @PostMapping("/device/pair")
  public DeviceSession pair(@Valid @RequestBody Pair body) {
    return service.pair(body.pairingToken());
  }
}
