package io.autolog.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class Db {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public Db(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
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

  public List<Map<String, Object>> list(String sql, Object... args) {
    return jdbc.query(
        sql,
        (rs, n) -> {
          Map<String, Object> row = new LinkedHashMap<>();
          var meta = rs.getMetaData();
          for (int i = 1; i <= meta.getColumnCount(); i++) {
            Object value = rs.getObject(i);
            if (value instanceof Timestamp timestamp) value = timestamp.toInstant();
            if (value instanceof PGobject pg
                && ("jsonb".equals(pg.getType()) || "json".equals(pg.getType()))) {
              try {
                value = json.readValue(pg.getValue(), Object.class);
              } catch (JsonProcessingException ex) {
                throw new SQLException("Invalid stored JSON", ex);
              }
            }
            row.put(camel(meta.getColumnLabel(i)), value);
          }
          return row;
        },
        parameters(args));
  }

  public Optional<Map<String, Object>> optional(String sql, Object... args) {
    return list(sql, args).stream().findFirst();
  }

  public Map<String, Object> one(String sql, Object... args) {
    return optional(sql, args).orElseThrow(ApiException::missing);
  }

  public int update(String sql, Object... args) {
    return jdbc.update(sql, parameters(args));
  }

  public String json(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(ex);
    }
  }

  public static UUID uuid(Map<String, Object> row, String key) {
    return (UUID) row.get(key);
  }

  public static String text(Map<String, Object> row, String key) {
    return (String) row.get(key);
  }

  public static long number(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  public static BigDecimal decimal(Map<String, Object> row, String key) {
    return (BigDecimal) row.get(key);
  }

  private static Object[] parameters(Object[] args) {
    return Arrays.stream(args).map(v -> v instanceof Instant i ? Timestamp.from(i) : v).toArray();
  }

  private static String camel(String snake) {
    StringBuilder out = new StringBuilder();
    boolean upper = false;
    for (char c : snake.toCharArray()) {
      if (c == '_') upper = true;
      else {
        out.append(upper ? Character.toUpperCase(c) : c);
        upper = false;
      }
    }
    return out.toString();
  }
}
