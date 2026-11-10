package io.autolog.common;

import java.time.*;
import java.time.format.DateTimeParseException;

public record ReportPeriod(Instant from, Instant to) {
  public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

  public static ReportPeriod month(String month) {
    if (month == null || !month.matches("[0-9]{4}-[0-9]{2}") || month.startsWith("0000"))
      throw ApiException.bad("month는 YYYY-MM 형식이어야 합니다.");
    try {
      var parsed = YearMonth.parse(month);
      return new ReportPeriod(
          parsed.atDay(1).atStartOfDay(ZONE).toInstant(),
          parsed.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant());
    } catch (DateTimeParseException ex) {
      throw ApiException.bad("month는 YYYY-MM 형식이어야 합니다.");
    }
  }
}
