package io.autolog.auth;

import static io.autolog.auth.AuthModels.*;
import static io.autolog.common.JdbcValues.*;

import io.autolog.common.Db;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class CredentialRepository {
  private static final RowMapper<Credential> CREDENTIAL =
      (r, n) ->
          new Credential(
              uuid(r, "id"), uuid(r, "company_id"), uuid(r, "admin_id"), uuid(r, "vehicle_id"));
  private final Db db;

  public CredentialRepository(Db db) {
    this.db = db;
  }

  public void create(
      UUID company, UUID admin, UUID vehicle, String hash, CredentialKind kind, Instant expires) {
    db.update(
        "INSERT INTO credentials(id,company_id,admin_id,vehicle_id,token_hash,kind,expires_at) VALUES(?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        company,
        admin,
        vehicle,
        hash,
        kind.name(),
        expires);
  }

  public Optional<Credential> find(String hash, CredentialKind kind) {
    return db.optional(
        "SELECT id,company_id,admin_id,vehicle_id FROM credentials WHERE token_hash=? AND kind=?",
        CREDENTIAL,
        hash,
        kind.name());
  }

  public boolean lockValid(UUID id) {
    return db.optional(
            "SELECT id,company_id,admin_id,vehicle_id FROM credentials WHERE id=? AND revoked_at IS NULL AND expires_at>now() FOR UPDATE",
            CREDENTIAL,
            id)
        .isPresent();
  }

  public void revoke(UUID id) {
    db.update("UPDATE credentials SET revoked_at=now() WHERE id=?", id);
  }

  public void revokeAdmin(UUID company, UUID admin) {
    db.update(
        "UPDATE credentials SET revoked_at=now() WHERE company_id=? AND admin_id=? AND revoked_at IS NULL",
        company,
        admin);
  }

  public void revokeSetup(UUID company, UUID admin) {
    db.update(
        "UPDATE credentials SET revoked_at=now() WHERE company_id=? AND admin_id=? AND kind='SETUP' AND revoked_at IS NULL",
        company,
        admin);
  }

  public void revokeVehicle(UUID company, UUID vehicle) {
    db.update(
        "UPDATE credentials SET revoked_at=now() WHERE company_id=? AND vehicle_id=? AND revoked_at IS NULL",
        company,
        vehicle);
  }

  public void revokePairing(UUID company, UUID vehicle) {
    db.update(
        "UPDATE credentials SET revoked_at=now() WHERE company_id=? AND vehicle_id=? AND kind='PAIRING' AND revoked_at IS NULL",
        company,
        vehicle);
  }

  public Optional<Actor> authenticate(String tokenHash) {
    return db.optional(
        """
      SELECT c.id AS credential_id,c.company_id,c.admin_id,c.vehicle_id,
             CASE WHEN c.kind='DEVICE' THEN 'DEVICE' ELSE a.role END AS role
      FROM credentials c LEFT JOIN admins a ON a.id=c.admin_id
      LEFT JOIN vehicles v ON v.id=c.vehicle_id
      WHERE c.token_hash=? AND c.revoked_at IS NULL AND c.expires_at>now()
      AND ((c.kind='ADMIN' AND a.active=true AND a.password_hash IS NOT NULL)
        OR (c.kind='DEVICE' AND v.active=true))
      """,
        (r, n) ->
            new Actor(
                uuid(r, "company_id"),
                uuid(r, "admin_id"),
                uuid(r, "vehicle_id"),
                uuid(r, "credential_id"),
                r.getString("role")),
        tokenHash);
  }
}
