package io.autolog.common;

import java.sql.*;
import java.time.Instant;
import java.util.UUID;

public final class JdbcValues {
  private JdbcValues() {}

  public static Instant instant(ResultSet row, String column) throws SQLException {
    Timestamp value = row.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  public static UUID uuid(ResultSet row, String column) throws SQLException {
    return row.getObject(column, UUID.class);
  }

  public static <E extends Enum<E>> E enumeration(ResultSet row, String column, Class<E> type)
      throws SQLException {
    String value = row.getString(column);
    return value == null ? null : Enum.valueOf(type, value);
  }
}
