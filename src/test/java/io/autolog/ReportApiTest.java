package io.autolog;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ReportApiTest extends ApiTestSupport {
  private static final String START = "2026-10-10T00:00:00Z", END = "2026-10-10T01:00:00Z";

  @Test
  void statisticsSeparateApprovedUnreviewedIncompleteAndUnknownDistance() throws Exception {
    approved("BUSINESS", START, END, 100, 110);
    approved("COMMUTE", "2026-10-11T00:00:00Z", "2026-10-11T01:00:00Z", 115, 120);
    approved("PERSONAL", "2026-10-12T00:00:00Z", "2026-10-12T01:00:00Z", 120, 124);
    completed(
        UUID.randomUUID().toString(), "2026-10-13T00:00:00Z", "2026-10-13T01:00:00Z", 124, 130);
    var running = syncBody(1, 0, "RUNNING", "2026-10-14T00:00:00Z", null, 130, null);
    running.put("lastOdometerKm", 133);
    running.put("lastObservedAt", "2026-10-14T01:00:00Z");
    call("PUT", "/api/device/trips/" + UUID.randomUUID(), device, running, 200);
    completed(
        UUID.randomUUID().toString(), "2026-10-15T00:00:00Z", "2026-10-15T01:00:00Z", null, 140);
    var stats = call("GET", "/api/statistics?month=2026-10", owner, null, 200);
    assertThat(stats.path("knownCompletedKm").decimalValue()).isEqualByComparingTo("25");
    assertThat(stats.path("approvedKm").decimalValue()).isEqualByComparingTo("19");
    assertThat(stats.path("approvedCommuteKm").decimalValue()).isEqualByComparingTo("5");
    assertThat(stats.path("approvedBusinessKm").decimalValue()).isEqualByComparingTo("10");
    assertThat(stats.path("approvedPersonalKm").decimalValue()).isEqualByComparingTo("4");
    assertThat(stats.path("unreviewedKnownKm").decimalValue()).isEqualByComparingTo("6");
    assertThat(stats.path("incompleteObservedKm").decimalValue()).isEqualByComparingTo("3");
    assertThat(stats.path("gaps").path("knownGapKm").decimalValue()).isEqualByComparingTo("5");
    assertThat(stats.path("measurementIssueCount").asInt()).isEqualTo(1);
    assertThat(stats.path("unconfirmedCount").asInt()).isEqualTo(3);
    assertThat(stats.path("approvedRecordBusinessRatioPercent").decimalValue())
        .isEqualByComparingTo("78.95");
  }

  @Test
  void monthBoundariesUseKoreaTimeAndStartAttribution() throws Exception {
    approved("BUSINESS", "2026-09-30T14:59:00Z", "2026-09-30T15:01:00Z", 100, 101);
    approved("BUSINESS", "2026-09-30T15:01:00Z", "2026-09-30T16:00:00Z", 101, 103);
    approved("BUSINESS", "2026-10-31T14:59:00Z", "2026-10-31T15:01:00Z", 103, 106);
    approved("BUSINESS", "2026-10-31T15:01:00Z", "2026-10-31T16:00:00Z", 106, 110);
    var october = call("GET", "/api/statistics?month=2026-10", owner, null, 200);
    assertThat(october.path("approvedCount").asInt()).isEqualTo(2);
    assertThat(october.path("approvedKm").decimalValue()).isEqualByComparingTo("5");
    call("GET", "/api/statistics?month=2026-99", owner, null, 400);
    call("GET", "/api/statistics?month=0000-01", owner, null, 400);
  }

  @Test
  void monthlyWorkbookHasOnlyApprovedRowsAndSafeLiteralStrings() throws Exception {
    String id = approved("BUSINESS", START, END, 100, 110);
    completed(
        UUID.randomUUID().toString(), "2026-10-11T00:00:00Z", "2026-10-11T01:00:00Z", 110, 120);
    var edit = details(3, "BUSINESS");
    edit.put("note", "=HYPERLINK(\"https://example.test\",\"test\")");
    call("PATCH", "/api/trips/" + id, owner, edit, 200);
    call("POST", "/api/trips/" + id + "/approve", owner, Map.of("expectedVersion", 4), 200);
    var response =
        response("GET", "/api/reports/monthly.xlsx?month=2026-10", owner, null).getResponse();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("Content-Disposition")).contains("attachment", "2026-10.xlsx");
    try (var book = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
      var sheet = book.getSheet("승인 운행기록");
      assertThat(sheet.getLastRowNum()).isEqualTo(2);
      assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo(id);
      assertThat(sheet.getRow(1).getCell(10).getNumericCellValue()).isEqualTo(10);
      assertThat(sheet.getRow(2).getCell(10).getNumericCellValue()).isEqualTo(10);
      assertThat(sheet.getRow(1).getCell(13).getCellType()).isEqualTo(CellType.STRING);
      assertThat(book.getSheet("검토 필요 기록").getLastRowNum()).isEqualTo(1);
      for (var s : book)
        for (var row : s)
          for (var cell : row) assertThat(cell.getCellType()).isNotEqualTo(CellType.FORMULA);
    }
    call("PATCH", "/api/trips/" + id, owner, details(5, "PERSONAL"), 200);
    try (var book =
        new XSSFWorkbook(
            new ByteArrayInputStream(
                response("GET", "/api/reports/monthly.xlsx?month=2026-10", owner, null)
                    .getResponse()
                    .getContentAsByteArray()))) {
      assertThat(book.getSheet("승인 운행기록").getLastRowNum()).isEqualTo(1);
      assertThat(book.getSheet("검토 필요 기록").getLastRowNum()).isEqualTo(2);
    }
  }

  @Test
  void ntsDraftMapsOfficialFieldsAndLeavesUnconfirmedLegalFieldsBlank() throws Exception {
    approved("COMMUTE", START, END, 100, 110);
    var response =
        response(
                "GET",
                "/api/reports/nts-draft.xlsx?month=2026-10&vehicleId=" + vehicle,
                owner,
                null)
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(200);
    try (var book = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
      var sheet = book.getSheet("차량1");
      assertThat(sheet).isNotNull();
      assertThat(sheet.getRow(1).getCell(3).getCellType()).isEqualTo(CellType.BLANK);
      assertThat(sheet.getRow(5).getCell(1).getStringCellValue()).isEqualTo("개발팀");
      assertThat(sheet.getRow(5).getCell(2).getStringCellValue()).isEqualTo("홍길동");
      assertThat(sheet.getRow(5).getCell(3).getNumericCellValue()).isEqualTo(100);
      assertThat(sheet.getRow(5).getCell(4).getNumericCellValue()).isEqualTo(110);
      assertThat(sheet.getRow(5).getCell(6).getNumericCellValue()).isEqualTo(10);
      assertThat(sheet.getRow(5).getCell(7).getNumericCellValue()).isZero();
      assertThat(sheet.getRow(5).getCell(10).getStringCellValue()).isEqualTo("홍길동");
      assertThat(sheet.getRow(7).getCell(1).getCellType()).isEqualTo(CellType.BLANK);
      assertThat(book.getSheet("안내").getRow(6).getCell(1).getStringCellValue())
          .isEqualTo("autolog-nts-2025-8-v2");
    }
  }

  @Test
  void reportsAndStatisticsDoNotLeakOtherCompanies() throws Exception {
    approved("BUSINESS", START, END, 100, 110);
    String other = signup("other@example.test").path("accessToken").asText();
    assertThat(
            call("GET", "/api/statistics?month=2026-10", other, null, 200)
                .path("tripCount")
                .asInt())
        .isZero();
    call("GET", "/api/reports/monthly.xlsx?month=2026-10&vehicleId=" + vehicle, other, null, 404);
    call("GET", "/api/trips/gaps?month=2026-10&vehicleId=" + vehicle, other, null, 404);
    call("GET", "/api/reports/monthly.xlsx?month=2026-10", device, null, 403);
    try (var book =
        new XSSFWorkbook(
            new ByteArrayInputStream(
                response("GET", "/api/reports/monthly.xlsx?month=2026-10", other, null)
                    .getResponse()
                    .getContentAsByteArray()))) {
      assertThat(book.getSheet("승인 운행기록").getLastRowNum()).isEqualTo(1);
    }
  }

  @Test
  void vehicleSummaryIncludesDaysMonthEndEvidenceAndSeparateUnconfirmedDistance() throws Exception {
    approved("BUSINESS", "2026-09-30T14:00:00Z", "2026-09-30T15:00:00Z", 90, 100);
    approved("BUSINESS", START, END, 100, 110);
    approved("COMMUTE", "2026-10-10T02:00:00Z", "2026-10-10T03:00:00Z", 110, 115);
    String pending = UUID.randomUUID().toString();
    completed(pending, "2026-10-11T00:00:00Z", "2026-10-11T01:00:00Z", 115, 120);
    call("POST", "/api/device/trips/" + pending + "/confirm", device, details(1, "BUSINESS"), 200);
    String unconfirmed = UUID.randomUUID().toString();
    completed(unconfirmed, "2026-10-12T00:00:00Z", "2026-10-12T01:00:00Z", 120, 127);
    call(
        "POST",
        "/api/vehicles",
        owner,
        Map.of("registrationNumber", "34나5678", "model", "기록 없는 차량", "active", true),
        201);
    var stats = call("GET", "/api/statistics?month=2026-10", owner, null, 200);
    assertThat(stats.path("unconfirmedKnownKm").asInt()).isEqualTo(7);
    assertThat(stats.path("pendingApprovalKm").asInt()).isEqualTo(5);
    assertThat(stats.path("unreviewedKnownKm").asInt()).isEqualTo(12);
    assertThat(stats.path("vehicles").size()).isEqualTo(2);
    var summary = stats.path("vehicles").get(0);
    assertThat(summary.path("approvedDrivingDays").asInt()).isEqualTo(1);
    assertThat(summary.path("approvedDurationSeconds").asInt()).isEqualTo(7200);
    assertThat(summary.path("previousMonthEnd").path("odometerKm").asInt()).isEqualTo(100);
    assertThat(summary.path("previousMonthEnd").path("status").asText()).isEqualTo("AT_BOUNDARY");
    assertThat(summary.path("currentMonthEnd").path("odometerKm").asInt()).isEqualTo(115);
    assertThat(summary.path("currentMonthEnd").path("observedAt").asText())
        .isEqualTo("2026-10-10T03:00:00Z");
    assertThat(summary.path("currentMonthEnd").path("status").asText())
        .isEqualTo("LAST_KNOWN_REQUIRES_REVIEW");
    var empty = stats.path("vehicles").get(1);
    assertThat(empty.path("tripCount").asInt()).isZero();
    assertThat(empty.path("currentMonthEnd").path("odometerKm").isNull()).isTrue();
    assertThat(empty.path("currentMonthEnd").path("status").asText()).isEqualTo("MISSING");
    try (var book =
        new XSSFWorkbook(
            new ByteArrayInputStream(
                response("GET", "/api/reports/monthly.xlsx?month=2026-10", owner, null)
                    .getResponse()
                    .getContentAsByteArray()))) {
      var sheet = book.getSheet("차량별 월간 집계");
      assertThat(sheet.getLastRowNum()).isEqualTo(2);
      assertThat(sheet.getRow(1).getCell(2).getNumericCellValue()).isEqualTo(1);
      assertThat(sheet.getRow(1).getCell(3).getNumericCellValue()).isEqualTo(15);
      assertThat(sheet.getRow(1).getCell(7).getNumericCellValue()).isEqualTo(7);
      assertThat(sheet.getRow(1).getCell(8).getNumericCellValue()).isEqualTo(5);
      assertThat(sheet.getRow(2).getCell(12).getCellType()).isEqualTo(CellType.BLANK);
      assertThat(sheet.getRow(1).getCell(15).getStringCellValue()).contains("당월 말 계기판 확인 필요");
    }
    call(
        "POST",
        "/api/trips/" + pending + "/request-changes",
        owner,
        Map.of("expectedVersion", 2, "reason", "재확인"),
        200);
    var returned = call("GET", "/api/statistics?month=2026-10", owner, null, 200);
    assertThat(returned.path("unconfirmedKnownKm").asInt()).isEqualTo(12);
    assertThat(returned.path("pendingApprovalKm").asInt()).isZero();
    assertThat(returned.path("changesRequestedKm").asInt()).isEqualTo(5);
  }
}
