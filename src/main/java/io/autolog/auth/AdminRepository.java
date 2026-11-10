package io.autolog.auth;

import static io.autolog.auth.AuthModels.*;
import static io.autolog.common.JdbcValues.*;

import io.autolog.common.JdbcExecutor;
import java.util.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AdminRepository {
  private static final RowMapper<AdminAccount> ACCOUNT =
      (r, n) ->
          new AdminAccount(
              uuid(r, "id"),
              uuid(r, "company_id"),
              r.getString("email"),
              r.getString("name"),
              r.getString("password_hash"),
              enumeration(r, "role", Role.class),
              r.getBoolean("active"),
              instant(r, "created_at"));
  private final JdbcExecutor db;

  public AdminRepository(JdbcExecutor db) {
    this.db = db;
  }

  public void createCompany(UUID id, String name) {
    db.update("INSERT INTO companies(id,name) VALUES(?,?)", id, name);
  }

  public void createOwner(UUID company, UUID id, String email, String name, String hash) {
    db.update(
        "INSERT INTO admins(id,company_id,email,name,password_hash,role) VALUES(?,?,?,?,?,'OWNER')",
        id,
        company,
        email,
        name,
        hash);
  }

  public void createStaff(UUID company, UUID id, String email, String name) {
    db.update(
        "INSERT INTO admins(id,company_id,email,name,role) VALUES(?,?,?,?,'STAFF')",
        id,
        company,
        email,
        name);
  }

  public Optional<AdminAccount> lockByEmail(String email) {
    return db.optional("SELECT * FROM admins WHERE email=? FOR UPDATE", ACCOUNT, email);
  }

  public AdminAccount find(UUID company, UUID id, boolean lock) {
    return db.one(
        "SELECT * FROM admins WHERE company_id=? AND id=?" + (lock ? " FOR UPDATE" : ""),
        ACCOUNT,
        company,
        id);
  }

  public List<AdminSummary> list(UUID company) {
    return db.list(
        """
        SELECT id, email, name, role, active, (password_hash IS NOT NULL) AS password_configured,
        created_at
        FROM admins
        WHERE company_id=?
        ORDER BY created_at, id
        """,
        (r, n) ->
            new AdminSummary(
                uuid(r, "id"),
                r.getString("email"),
                r.getString("name"),
                enumeration(r, "role", Role.class),
                r.getBoolean("active"),
                r.getBoolean("password_configured"),
                instant(r, "created_at")),
        company);
  }

  public void setActive(UUID company, UUID id, boolean active) {
    db.update("UPDATE admins SET active=? WHERE company_id=? AND id=?", active, company, id);
  }

  public void setPassword(UUID company, UUID id, String hash) {
    db.update("UPDATE admins SET password_hash=? WHERE company_id=? AND id=?", hash, company, id);
  }
}
