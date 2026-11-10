package io.autolog.common;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcExecutor {
  private final JdbcTemplate jdbc;

  public JdbcExecutor(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public <T> List<T> list(String sql, RowMapper<T> mapper, Object... args) {
    return jdbc.query(sql, mapper, parameters(args));
  }

  public <T> Optional<T> optional(String sql, RowMapper<T> mapper, Object... args) {
    return list(sql, mapper, args).stream().findFirst();
  }

  public <T> T one(String sql, RowMapper<T> mapper, Object... args) {
    return optional(sql, mapper, args).orElseThrow(ApiException::missing);
  }

  public int update(String sql, Object... args) {
    return jdbc.update(sql, parameters(args));
  }

  private static Object[] parameters(Object[] args) {
    return Arrays.stream(args).map(v -> v instanceof Instant i ? Timestamp.from(i) : v).toArray();
  }
}
