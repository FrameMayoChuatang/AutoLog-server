package io.autolog.auth;

import static io.autolog.common.Db.*;

import io.autolog.common.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  private final Db db;
  private final PasswordEncoder passwords;
  private final String dummyHash;
  private final String setupUrl;
  private final Duration sessionTtl, setupTtl, deviceTtl, pairingTtl;

  public AuthService(
      Db db,
      PasswordEncoder passwords,
      @Value("${autolog.setup-url}") String setupUrl,
      @Value("${autolog.session-ttl}") Duration sessionTtl,
      @Value("${autolog.setup-ttl}") Duration setupTtl,
      @Value("${autolog.device-ttl}") Duration deviceTtl,
      @Value("${autolog.pairing-ttl}") Duration pairingTtl) {
    this.db = db;
    this.passwords = passwords;
    this.setupUrl = setupUrl;
    this.sessionTtl = sessionTtl;
    this.setupTtl = setupTtl;
    this.deviceTtl = deviceTtl;
    this.pairingTtl = pairingTtl;
    this.dummyHash = passwords.encode(UUID.randomUUID().toString());
  }

  public record IssuedToken(String token, Instant expiresAt) {}

  public record SetupLink(String setupToken, String setupLink, Instant expiresAt) {}

  public record Session(
      String accessToken, String tokenType, Instant expiresAt, Map<String, Object> admin) {}

  public IssuedToken issue(
      UUID companyId, UUID adminId, UUID vehicleId, String kind, Duration ttl) {
    String raw = TokenCodec.create();
    Instant expires = Instant.now().plus(ttl);
    db.update(
        "INSERT INTO credentials(id,company_id,admin_id,vehicle_id,token_hash,kind,expires_at) VALUES(?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        companyId,
        adminId,
        vehicleId,
        TokenCodec.hash(raw),
        kind,
        expires);
    return new IssuedToken(raw, expires);
  }

  private Session session(UUID companyId, UUID adminId) {
    var token = issue(companyId, adminId, null, "ADMIN", sessionTtl);
    return new Session(token.token(), "Bearer", token.expiresAt(), publicAdmin(companyId, adminId));
  }

  private Map<String, Object> publicAdmin(UUID companyId, UUID id) {
    return db.one(
        "SELECT id,company_id,email,name,role,active,created_at FROM admins WHERE company_id=? AND id=?",
        companyId,
        id);
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
    db.update("INSERT INTO companies(id,name) VALUES(?,?)", company, companyName.strip());
    db.update(
        "INSERT INTO admins(id,company_id,email,name,password_hash,role) VALUES(?,?,?,?,?,'OWNER')",
        owner,
        company,
        email(email),
        ownerName.strip(),
        passwords.encode(password));
    return session(company, owner);
  }

  @Transactional
  public Session login(String email, String password) {
    var found = db.optional("SELECT * FROM admins WHERE email=? FOR UPDATE", email(email));
    String hash = found.map(r -> text(r, "passwordHash")).orElse(dummyHash);
    // BCrypt accepts at most 72 UTF-8 bytes. Reject oversized login input without truncation.
    if (password.getBytes(StandardCharsets.UTF_8).length > 72) throw ApiException.unauthorized();
    boolean matches = passwords.matches(password, hash);
    if (!matches || found.isEmpty() || !Boolean.TRUE.equals(found.get().get("active")))
      throw ApiException.unauthorized();
    var row = found.get();
    return session(uuid(row, "companyId"), uuid(row, "id"));
  }

  public Map<String, Object> me(Actor actor) {
    if (actor.adminId() != null) return publicAdmin(actor.companyId(), actor.adminId());
    return db.one(
        "SELECT id,company_id,registration_number,model,active FROM vehicles WHERE company_id=? AND id=?",
        actor.companyId(),
        actor.vehicleId());
  }

  public void logout(Actor actor) {
    db.update("UPDATE credentials SET revoked_at=now() WHERE id=?", actor.credentialId());
  }

  public List<Map<String, Object>> admins(Actor actor) {
    actor.requireOwner();
    return db.list(
        "SELECT id,email,name,role,active,(password_hash IS NOT NULL) AS password_configured,created_at FROM admins WHERE company_id=? ORDER BY created_at,id",
        actor.companyId());
  }

  @Transactional
  public Map<String, Object> createStaff(Actor actor, String email, String name) {
    actor.requireOwner();
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO admins(id,company_id,email,name,role) VALUES(?,?,?,?,'STAFF')",
        id,
        actor.companyId(),
        email(email),
        name.strip());
    return Map.of(
        "admin", publicAdmin(actor.companyId(), id), "invitation", setup(actor.companyId(), id));
  }

  private SetupLink setup(UUID companyId, UUID id) {
    db.update(
        "UPDATE credentials SET revoked_at=now() WHERE company_id=? AND admin_id=? AND kind='SETUP' AND revoked_at IS NULL",
        companyId,
        id);
    var token = issue(companyId, id, null, "SETUP", setupTtl);
    return new SetupLink(token.token(), setupUrl + "#token=" + token.token(), token.expiresAt());
  }

  @Transactional
  public SetupLink reissueSetup(Actor actor, UUID id) {
    actor.requireOwner();
    var admin =
        db.one(
            "SELECT * FROM admins WHERE company_id=? AND id=? FOR UPDATE", actor.companyId(), id);
    if (!"STAFF".equals(text(admin, "role")) || !Boolean.TRUE.equals(admin.get("active")))
      throw ApiException.conflict("활성 직원 관리자에게만 설정 링크를 발급할 수 있습니다.");
    return setup(actor.companyId(), id);
  }

  @Transactional
  public Map<String, Object> setStaffActive(Actor actor, UUID id, boolean active) {
    actor.requireOwner();
    var admin =
        db.one(
            "SELECT * FROM admins WHERE company_id=? AND id=? FOR UPDATE", actor.companyId(), id);
    if (!"STAFF".equals(text(admin, "role"))) throw ApiException.denied();
    db.update(
        "UPDATE admins SET active=? WHERE company_id=? AND id=?", active, actor.companyId(), id);
    if (!active)
      db.update(
          "UPDATE credentials SET revoked_at=now() WHERE company_id=? AND admin_id=? AND revoked_at IS NULL",
          actor.companyId(),
          id);
    return publicAdmin(actor.companyId(), id);
  }

  @Transactional
  public Session finishSetup(String token, String password) {
    password(password);
    var credential =
        db.optional(
                "SELECT * FROM credentials WHERE token_hash=? AND kind='SETUP'",
                TokenCodec.hash(token))
            .orElseThrow(ApiException::unauthorized);
    var admin =
        db.one(
            "SELECT * FROM admins WHERE company_id=? AND id=? FOR UPDATE",
            uuid(credential, "companyId"),
            uuid(credential, "adminId"));
    var valid =
        db.optional(
            "SELECT * FROM credentials WHERE id=? AND revoked_at IS NULL AND expires_at>now() FOR UPDATE",
            uuid(credential, "id"));
    if (valid.isEmpty() || !Boolean.TRUE.equals(admin.get("active")))
      throw ApiException.unauthorized();
    UUID company = uuid(admin, "companyId"), id = uuid(admin, "id");
    db.update(
        "UPDATE admins SET password_hash=? WHERE company_id=? AND id=?",
        passwords.encode(password),
        company,
        id);
    db.update(
        "UPDATE credentials SET revoked_at=now() WHERE company_id=? AND admin_id=? AND revoked_at IS NULL",
        company,
        id);
    return session(company, id);
  }

  @Transactional
  public IssuedToken pairingCode(Actor actor, UUID vehicle) {
    actor.requireAdmin();
    var row =
        db.one(
            "SELECT * FROM vehicles WHERE company_id=? AND id=? FOR UPDATE",
            actor.companyId(),
            vehicle);
    if (!Boolean.TRUE.equals(row.get("active"))) throw ApiException.conflict("비활성 차량입니다.");
    db.update(
        "UPDATE credentials SET revoked_at=now() WHERE company_id=? AND vehicle_id=? AND kind='PAIRING' AND revoked_at IS NULL",
        actor.companyId(),
        vehicle);
    return issue(actor.companyId(), null, vehicle, "PAIRING", pairingTtl);
  }

  @Transactional
  public Map<String, Object> pair(String token) {
    var credential =
        db.optional(
                "SELECT * FROM credentials WHERE token_hash=? AND kind='PAIRING'",
                TokenCodec.hash(token))
            .orElseThrow(ApiException::unauthorized);
    UUID company = uuid(credential, "companyId"), vehicle = uuid(credential, "vehicleId");
    var row =
        db.one("SELECT * FROM vehicles WHERE company_id=? AND id=? FOR UPDATE", company, vehicle);
    var valid =
        db.optional(
            "SELECT * FROM credentials WHERE id=? AND revoked_at IS NULL AND expires_at>now() FOR UPDATE",
            uuid(credential, "id"));
    if (valid.isEmpty() || !Boolean.TRUE.equals(row.get("active")))
      throw ApiException.unauthorized();
    db.update(
        "UPDATE credentials SET revoked_at=now() WHERE company_id=? AND vehicle_id=? AND revoked_at IS NULL",
        company,
        vehicle);
    var device = issue(company, null, vehicle, "DEVICE", deviceTtl);
    return Map.of(
        "accessToken",
        device.token(),
        "tokenType",
        "Bearer",
        "expiresAt",
        device.expiresAt(),
        "vehicle",
        row);
  }
}
