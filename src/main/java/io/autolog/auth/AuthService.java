package io.autolog.auth;

import static io.autolog.auth.AuthModels.*;

import io.autolog.common.ApiException;
import io.autolog.fleet.FleetRepository;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  private final AdminRepository admins;
  private final CredentialRepository credentials;
  private final FleetRepository fleet;
  private final PasswordEncoder passwords;
  private final ReviewPairingConfig reviewPairing;
  private final String dummyHash, setupUrl;
  private final Duration sessionTtl, setupTtl, deviceTtl, pairingTtl;

  public AuthService(
      AdminRepository admins,
      CredentialRepository credentials,
      FleetRepository fleet,
      PasswordEncoder passwords,
      ReviewPairingConfig reviewPairing,
      @Value("${autolog.setup-url}") String setupUrl,
      @Value("${autolog.session-ttl}") Duration sessionTtl,
      @Value("${autolog.setup-ttl}") Duration setupTtl,
      @Value("${autolog.device-ttl}") Duration deviceTtl,
      @Value("${autolog.pairing-ttl}") Duration pairingTtl) {
    this.admins = admins;
    this.credentials = credentials;
    this.fleet = fleet;
    this.passwords = passwords;
    this.reviewPairing = reviewPairing;
    this.setupUrl = setupUrl;
    this.sessionTtl = sessionTtl;
    this.setupTtl = setupTtl;
    this.deviceTtl = deviceTtl;
    this.pairingTtl = pairingTtl;
    this.dummyHash = passwords.encode(UUID.randomUUID().toString());
  }

  private IssuedToken issue(
      UUID company, UUID admin, UUID vehicle, CredentialKind kind, Duration ttl) {
    String raw = TokenCodec.create();
    Instant expires = Instant.now().plus(ttl);
    credentials.create(company, admin, vehicle, TokenCodec.hash(raw), kind, expires);
    return new IssuedToken(raw, expires);
  }

  private Session session(UUID company, UUID admin) {
    var token = issue(company, admin, null, CredentialKind.ADMIN, sessionTtl);
    return new Session(
        token.token(), "Bearer", token.expiresAt(), admins.find(company, admin, false).profile());
  }

  private static String email(String input) {
    return input.strip().toLowerCase(Locale.ROOT);
  }

  private static void password(String input) {
    if (input.length() < 10 || input.getBytes(StandardCharsets.UTF_8).length > 72)
      throw ApiException.bad("비밀번호는 10자 이상, UTF-8 기준 72바이트 이하여야 합니다.");
  }

  @Transactional
  public Session signup(String companyName, String ownerName, String email, String password) {
    password(password);
    UUID company = UUID.randomUUID(), owner = UUID.randomUUID();
    admins.createCompany(company, companyName.strip());
    admins.createOwner(company, owner, email(email), ownerName.strip(), passwords.encode(password));
    return session(company, owner);
  }

  @Transactional
  public Session login(String email, String password) {
    var found = admins.lockByEmail(email(email));
    String hash = found.map(AdminAccount::passwordHash).orElse(dummyHash);
    // BCrypt must never silently truncate an oversized UTF-8 password.
    if (password.getBytes(StandardCharsets.UTF_8).length > 72) throw ApiException.unauthorized();
    boolean matches = passwords.matches(password, hash);
    if (!matches || found.isEmpty() || !found.get().active()) throw ApiException.unauthorized();
    var account = found.get();
    return session(account.companyId(), account.id());
  }

  public CurrentUser me(Actor actor) {
    return actor.adminId() != null
        ? admins.find(actor.companyId(), actor.adminId(), false).profile()
        : VehicleIdentity.from(fleet.vehicle(actor.companyId(), actor.vehicleId(), false));
  }

  public void logout(Actor actor) {
    credentials.revoke(actor.credentialId());
  }

  public List<AdminSummary> admins(Actor actor) {
    actor.requireOwner();
    return admins.list(actor.companyId());
  }

  @Transactional
  public StaffInvitation createStaff(Actor actor, String email, String name) {
    actor.requireOwner();
    var id = UUID.randomUUID();
    admins.createStaff(actor.companyId(), id, email(email), name.strip());
    return new StaffInvitation(
        admins.find(actor.companyId(), id, false).profile(), setup(actor.companyId(), id));
  }

  private SetupLink setup(UUID company, UUID id) {
    credentials.revokeSetup(company, id);
    var token = issue(company, id, null, CredentialKind.SETUP, setupTtl);
    return new SetupLink(token.token(), setupUrl + "#token=" + token.token(), token.expiresAt());
  }

  @Transactional
  public SetupLink reissueSetup(Actor actor, UUID id) {
    actor.requireOwner();
    var admin = admins.find(actor.companyId(), id, true);
    if (admin.role() != Role.STAFF || !admin.active())
      throw ApiException.conflict("활성 직원 관리자에게만 설정 링크를 발급할 수 있습니다.");
    return setup(actor.companyId(), id);
  }

  @Transactional
  public AdminProfile setStaffActive(Actor actor, UUID id, boolean active) {
    actor.requireOwner();
    var admin = admins.find(actor.companyId(), id, true);
    if (admin.role() != Role.STAFF) throw ApiException.denied();
    admins.setActive(actor.companyId(), id, active);
    if (!active) credentials.revokeAdmin(actor.companyId(), id);
    return admins.find(actor.companyId(), id, false).profile();
  }

  @Transactional
  public Session finishSetup(String token, String password) {
    password(password);
    var credential =
        credentials
            .find(TokenCodec.hash(token), CredentialKind.SETUP)
            .orElseThrow(ApiException::unauthorized);
    var admin = admins.find(credential.companyId(), credential.adminId(), true);
    if (!credentials.lockValid(credential.id()) || !admin.active())
      throw ApiException.unauthorized();
    admins.setPassword(admin.companyId(), admin.id(), passwords.encode(password));
    credentials.revokeAdmin(admin.companyId(), admin.id());
    return session(admin.companyId(), admin.id());
  }

  @Transactional
  public IssuedToken pairingCode(Actor actor, UUID vehicle) {
    actor.requireAdmin();
    if (!fleet.vehicle(actor.companyId(), vehicle, true).active())
      throw ApiException.conflict("비활성 차량입니다.");
    credentials.revokePairing(actor.companyId(), vehicle);
    Instant expires = Instant.now().plus(pairingTtl);
    for (int attempt = 0; attempt < 32; attempt++) {
      String code = TokenCodec.createPairingCode();
      String hash = TokenCodec.hash(code);
      if (reviewPairing.matches(hash)) continue;
      if (credentials.tryCreatePairing(actor.companyId(), vehicle, hash, expires))
        return new IssuedToken(code, expires);
    }
    throw ApiException.conflict("페어링 코드를 발급하지 못했습니다. 다시 시도하세요.");
  }

  @Transactional
  public DeviceSession pair(String token) {
    String hash = TokenCodec.hash(token);
    if (reviewPairing.matches(hash)) {
      // Reject collisions with previously issued codes instead of connecting another company's app.
      if (credentials.find(hash, CredentialKind.PAIRING).isPresent())
        throw ApiException.conflict("기존 페어링 코드와 중복됩니다. 심사용 코드를 변경해 주세요.");
      var vehicle = fleet.vehicle(reviewPairing.companyId(), reviewPairing.vehicleId(), true);
      if (!vehicle.active()) throw ApiException.conflict("비활성 차량입니다.");
      // Each reviewer receives a separate expiring device token without disconnecting other
      // devices.
      var device = issue(vehicle.companyId(), null, vehicle.id(), CredentialKind.DEVICE, deviceTtl);
      return new DeviceSession(device.token(), "Bearer", device.expiresAt(), vehicle);
    }
    var credential =
        credentials.find(hash, CredentialKind.PAIRING).orElseThrow(ApiException::unauthorized);
    var vehicle = fleet.vehicle(credential.companyId(), credential.vehicleId(), true);
    if (!credentials.lockValid(credential.id()) || !vehicle.active())
      throw ApiException.unauthorized();
    credentials.revokeVehicle(credential.companyId(), credential.vehicleId());
    var device =
        issue(
            credential.companyId(), null, credential.vehicleId(), CredentialKind.DEVICE, deviceTtl);
    return new DeviceSession(device.token(), "Bearer", device.expiresAt(), vehicle);
  }
}
