package io.autolog.report;

import static io.autolog.common.Db.*;

import io.autolog.auth.Actor;
import io.autolog.common.*;
import io.autolog.trip.TripQueryService;
import java.io.*;
import java.math.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ReportService {
  public static final String TEMPLATE_VERSION = "autolog-nts-2025-8-v1";
  public static final String NTS_SOURCE =
      "https://www.nts.go.kr/nts/na/ntt/selectNttInfo.do?mi=2205&nttSn=1341754";
  private final Db db;
  private final TripQueryService query;

  public ReportService(Db db, TripQueryService query) {
    this.db = db;
    this.query = query;
  }

  private static BigDecimal sum(List<Map<String, Object>> rows, String key) {
    return rows.stream()
        .map(r -> decimal(r, key))
        .filter(Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static List<Map<String, Object>> approved(List<Map<String, Object>> rows) {
    return rows.stream().filter(r -> Boolean.TRUE.equals(r.get("reportEligible"))).toList();
  }

  private static List<Map<String, Object>> category(
      List<Map<String, Object>> rows, String category) {
    return rows.stream().filter(r -> category.equals(text(r, "classification"))).toList();
  }

  private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
    return denominator.signum() == 0
        ? null
        : numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 2, RoundingMode.HALF_UP);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> stats(Actor actor, String month, UUID vehicleId) {
    var rows = query.monthly(actor, month, vehicleId);
    var approved = approved(rows);
    BigDecimal approvedKm = sum(approved, "distanceKm"),
        commute = sum(category(approved, "COMMUTE"), "distanceKm"),
        business = sum(category(approved, "BUSINESS"), "distanceKm");
    var result = new LinkedHashMap<String, Object>();
    result.put("month", month);
    result.put("timezone", TripQueryService.REPORT_ZONE.toString());
    result.put("tripCount", rows.size());
    result.put("knownCompletedKm", sum(rows, "distanceKm"));
    result.put("approvedKm", approvedKm);
    result.put("approvedCommuteKm", commute);
    result.put("approvedBusinessKm", business);
    result.put("approvedPersonalKm", sum(category(approved, "PERSONAL"), "distanceKm"));
    result.put("unreviewedKnownKm", sum(rows, "distanceKm").subtract(approvedKm));
    result.put(
        "incompleteObservedKm",
        sum(
            rows.stream().filter(r -> !"COMPLETED".equals(text(r, "state"))).toList(),
            "observedDistanceKm"));
    result.put("approvedRecordBusinessRatioPercent", ratio(commute.add(business), approvedKm));
    result.put("approvedCount", approved.size());
    result.put(
        "incompleteCount",
        rows.stream().filter(r -> !"COMPLETED".equals(text(r, "state"))).count());
    result.put(
        "unconfirmedCount", rows.stream().filter(r -> r.get("driverConfirmedAt") == null).count());
    result.put(
        "measurementIssueCount",
        rows.stream()
            .filter(r -> "COMPLETED".equals(text(r, "state")) && r.get("distanceKm") == null)
            .count());
    result.put(
        "pendingApprovalCount",
        rows.stream().filter(r -> "PENDING".equals(text(r, "approvalStatus"))).count());
    result.put(
        "changesRequestedCount",
        rows.stream().filter(r -> "CHANGES_REQUESTED".equals(text(r, "approvalStatus"))).count());
    result.put("gaps", query.gaps(actor, month, vehicleId));
    List<Map<String, Object>> byVehicle = new ArrayList<>();
    rows.stream()
        .map(r -> uuid(r, "vehicleId"))
        .distinct()
        .forEach(
            id -> {
              var subset = rows.stream().filter(r -> id.equals(uuid(r, "vehicleId"))).toList();
              var selected = approved(subset);
              byVehicle.add(
                  Map.of(
                      "vehicleId",
                      id,
                      "registrationNumber",
                      text(subset.getFirst(), "registrationNumber"),
                      "tripCount",
                      subset.size(),
                      "approvedCount",
                      selected.size(),
                      "approvedKm",
                      sum(selected, "distanceKm"),
                      "unreviewedKnownKm",
                      sum(subset, "distanceKm").subtract(sum(selected, "distanceKm"))));
            });
    result.put("vehicles", byVehicle);
    return result;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public byte[] workbook(Actor actor, String month, UUID vehicleId, boolean nts) {
    var rows = query.monthly(actor, month, vehicleId);
    var selected = approved(rows);
    if (rows.size() > 50000) throw ApiException.bad("엑셀은 한 번에 50,000건까지 출력할 수 있습니다. 차량을 선택해 주세요.");
    var gapResult = query.gaps(actor, month, vehicleId);
    var company = db.one("SELECT name FROM companies WHERE id=?", actor.companyId());
    try (var workbook = new XSSFWorkbook();
        var output = new ByteArrayOutputStream()) {
      var styles = new Styles(workbook);
      var info = workbook.createSheet("안내");
      write(info, 0, styles.title, "AutoLog " + (nts ? "업무용승용차 운행기록부 초안" : "월간 운행보고서"));
      write(info, 1, styles.normal, "법인명", text(company, "name"));
      write(info, 2, styles.normal, "조회 월 (Asia/Seoul)", month, "운행 시작 시각 기준");
      write(info, 3, styles.normal, "출력 기준", "운전자 확인 + 관리자 승인 + 정상 종료·측정값");
      write(info, 4, styles.normal, "포함 / 제외 건수", selected.size(), rows.size() - selected.size());
      write(
          info,
          5,
          styles.normal,
          "확인된 기록 간 공백 (km)",
          gapResult.get("knownGapKm"),
          "업무거리로 자동 분류하지 않음");
      write(info, 6, styles.normal, "양식 버전", nts ? TEMPLATE_VERSION : "autolog-monthly-v1");
      write(info, 7, styles.normal, "생성 시각 (UTC)", Instant.now().toString());
      write(info, 8, styles.normal, "거리 단위", "km; 원본 계기판 값을 보존하며 표시 소수점은 3자리");
      if (nts) {
        write(info, 10, styles.header, "제출 전 확인 항목");
        write(info, 11, styles.normal, "공식 근거", "국세청고시 제2025-8호 (법인세법), 2025-04-01", NTS_SOURCE);
        write(
            info,
            12,
            styles.normal,
            "사용자 확인",
            "공식 서식의 사용자는 운전자와 다를 수 있으므로 사용자 부서·성명은 비워 둠. 참고 운전자를 보고 확인 후 작성.");
        write(
            info,
            13,
            styles.normal,
            "사업연도·사업자등록번호",
            "확정 입력이 없어 빈칸으로 제공. 연간 총거리·업무거리·비율도 임의 산출하지 않음.");
        write(info, 14, styles.normal, "초안 범위", "선택한 월의 승인 기록만 포함. 미승인·미완료·미확인·공백은 검토 시트에서 확인.");
        write(info, 15, styles.normal, "기본정보 검토", "차종·등록번호는 현재 차량 정보. 과거 정보가 다르면 확인 후 수정.");
        write(info, 16, styles.normal, "업무목적", "이 출력만으로 세무상 비용 인정 또는 제출 완결성을 보증하지 않음.");
        LinkedHashSet<UUID> vehicles = new LinkedHashSet<>();
        rows.forEach(r -> vehicles.add(uuid(r, "vehicleId")));
        if (vehicleId != null) vehicles.add(vehicleId);
        int index = 1;
        for (UUID id : vehicles) {
          var vehicle =
              db.one("SELECT * FROM vehicles WHERE company_id=? AND id=?", actor.companyId(), id);
          var subset = selected.stream().filter(r -> id.equals(uuid(r, "vehicleId"))).toList();
          ntsSheet(workbook, styles, "차량" + index++, text(company, "name"), vehicle, month, subset);
        }
      } else {
        var sheet = workbook.createSheet("승인 운행기록");
        write(
            sheet,
            0,
            styles.header,
            "운행 ID",
            "차량번호",
            "차종",
            "시작 (KST)",
            "종료 (KST)",
            "운전자",
            "부서",
            "유형",
            "주행 전 (km)",
            "주행 후 (km)",
            "거리 (km)",
            "목적",
            "거래처",
            "비고",
            "승인 시각 (KST)");
        int index = 1;
        for (var trip : selected)
          write(
              sheet,
              index++,
              styles.normal,
              trip.get("id"),
              trip.get("registrationNumber"),
              trip.get("model"),
              time(trip.get("startedAt")),
              time(trip.get("endedAt")),
              trip.get("driverName"),
              trip.get("driverDepartment"),
              label(text(trip, "classification")),
              trip.get("startOdometerKm"),
              trip.get("endOdometerKm"),
              trip.get("distanceKm"),
              trip.get("purposeText"),
              trip.get("clientText"),
              trip.get("note"),
              time(trip.get("approvedAt")));
        write(
            sheet,
            index,
            styles.header,
            "합계",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            sum(selected, "distanceKm"));
        filter(sheet, 0, Math.max(0, index - 1), 15);
        sheet.createFreezePane(0, 1);
        var summary = workbook.createSheet("승인 거리 집계");
        write(summary, 0, styles.header, "구분", "거리 (km)");
        write(summary, 1, styles.normal, "출퇴근", sum(category(selected, "COMMUTE"), "distanceKm"));
        write(
            summary, 2, styles.normal, "일반 업무", sum(category(selected, "BUSINESS"), "distanceKm"));
        write(summary, 3, styles.normal, "개인", sum(category(selected, "PERSONAL"), "distanceKm"));
        write(summary, 4, styles.header, "승인 기록 합계", sum(selected, "distanceKm"));
      }
      reviewSheets(workbook, styles, rows, gapResult);
      for (Sheet sheet : workbook) {
        sheet.setDefaultColumnWidth(20);
        sheet.setColumnWidth(0, 30 * 256);
        sheet.setFitToPage(true);
        sheet.getPrintSetup().setLandscape(true);
        sheet.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
        sheet.getPrintSetup().setFitWidth((short) 1);
        sheet.getPrintSetup().setFitHeight((short) 0);
      }
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException ex) {
      throw new IllegalStateException("엑셀 생성 실패", ex);
    }
  }

  private static void ntsSheet(
      Workbook book,
      Styles styles,
      String name,
      String company,
      Map<String, Object> vehicle,
      String month,
      List<Map<String, Object>> trips) {
    Sheet sheet = book.createSheet(name);
    write(sheet, 0, styles.title, "업무용승용차 운행기록부 — 확인용 초안");
    sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 12));
    write(
        sheet, 1, styles.normal, "법인명", company, "사업자등록번호", null, "사업연도 시작", null, "사업연도 종료", null);
    write(
        sheet,
        2,
        styles.normal,
        "① 차종",
        vehicle.get("model"),
        "② 자동차등록번호",
        vehicle.get("registrationNumber"),
        "추출 월",
        month);
    write(sheet, 3, styles.normal, "④ 사용자는 직접 확인하여 작성. ⑪~⑬은 사업연도 전체 자료 확인 후 작성.");
    sheet.addMergedRegion(new CellRangeAddress(3, 3, 0, 12));
    write(
        sheet,
        4,
        styles.header,
        "③ 사용일자(요일)",
        "④ 사용자 부서",
        "④ 사용자 성명",
        "⑤ 주행 전 계기판 (km)",
        "⑥ 주행 후 계기판 (km)",
        "⑦ 주행거리 (km)",
        "⑧ 출퇴근용 (km)",
        "⑨ 일반 업무용 (km)",
        "⑩ 비고 / 목적",
        "참고 운전자 부서",
        "참고 운전자 성명",
        "운행 ID",
        "거래처");
    int index = 5;
    for (var trip : trips) {
      String classification = text(trip, "classification");
      write(
          sheet,
          index++,
          styles.normal,
          DateTimeFormatter.ofPattern("yyyy-MM-dd (E)", Locale.KOREAN)
              .withZone(TripQueryService.REPORT_ZONE)
              .format((Instant) trip.get("startedAt")),
          null,
          null,
          trip.get("startOdometerKm"),
          trip.get("endOdometerKm"),
          trip.get("distanceKm"),
          "COMMUTE".equals(classification) ? trip.get("distanceKm") : BigDecimal.ZERO,
          "BUSINESS".equals(classification) ? trip.get("distanceKm") : BigDecimal.ZERO,
          label(classification)
              + " / "
              + Objects.toString(trip.get("purposeText"), "")
              + " / "
              + Objects.toString(trip.get("note"), ""),
          trip.get("driverDepartment"),
          trip.get("driverName"),
          trip.get("id"),
          trip.get("clientText"));
    }
    write(
        sheet,
        index++,
        styles.header,
        "선택 월 승인 기록 합계",
        null,
        null,
        null,
        null,
        sum(trips, "distanceKm"),
        sum(category(trips, "COMMUTE"), "distanceKm"),
        sum(category(trips, "BUSINESS"), "distanceKm"));
    write(
        sheet,
        index,
        styles.header,
        "⑪ 사업연도 총주행거리",
        null,
        "⑫ 사업연도 업무용 사용거리",
        null,
        "⑬ 업무사용비율",
        null);
    filter(sheet, 4, Math.max(4, index - 2), 13);
    sheet.createFreezePane(0, 5);
    sheet.setRepeatingRows(new CellRangeAddress(0, 4, -1, -1));
  }

  @SuppressWarnings("unchecked")
  private static void reviewSheets(
      Workbook book, Styles styles, List<Map<String, Object>> rows, Map<String, Object> gaps) {
    Sheet excluded = book.createSheet("검토 필요 기록");
    write(
        excluded,
        0,
        styles.header,
        "운행 ID",
        "차량번호",
        "시작 (KST)",
        "기록 상태",
        "승인 상태",
        "운전자 확인",
        "품질 문제",
        "확인된 거리 (km)",
        "미완료 관측 거리 (km)");
    int i = 1;
    for (var trip : rows)
      if (!Boolean.TRUE.equals(trip.get("reportEligible")))
        write(
            excluded,
            i++,
            styles.normal,
            trip.get("id"),
            trip.get("registrationNumber"),
            time(trip.get("startedAt")),
            trip.get("state"),
            trip.get("approvalStatus"),
            trip.get("driverConfirmedAt") != null ? "확인" : "미확인",
            String.join(",", (List<String>) trip.get("qualityIssues")),
            trip.get("distanceKm"),
            trip.get("observedDistanceKm"));
    filter(excluded, 0, Math.max(0, i - 1), 9);
    excluded.createFreezePane(0, 1);
    Sheet gap = book.createSheet("기록 간 공백");
    write(
        gap,
        0,
        styles.header,
        "차량 ID",
        "이전 운행 ID",
        "다음 운행 ID",
        "다음 운행 시작 (KST)",
        "상태",
        "확인된 공백 (km)");
    i = 1;
    for (var row : (List<Map<String, Object>>) gaps.get("items"))
      write(
          gap,
          i++,
          styles.normal,
          row.get("vehicleId"),
          row.get("previousId"),
          row.get("id"),
          time(row.get("startedAt")),
          row.get("issue"),
          row.get("distanceKm"));
    gap.createFreezePane(0, 1);
  }

  private static String time(Object value) {
    return value instanceof Instant instant
        ? DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(TripQueryService.REPORT_ZONE)
            .format(instant)
        : "";
  }

  private static String label(String value) {
    return switch (value) {
      case "COMMUTE" -> "출퇴근";
      case "BUSINESS" -> "업무";
      case "PERSONAL" -> "개인";
      default -> "미확인";
    };
  }

  private static void filter(Sheet sheet, int first, int last, int columns) {
    sheet.setAutoFilter(new CellRangeAddress(first, last, 0, columns - 1));
  }

  private static void write(Sheet sheet, int index, CellStyle style, Object... values) {
    Row row = sheet.createRow(index);
    row.setHeightInPoints(30);
    for (int i = 0; i < values.length; i++) {
      Cell cell = row.createCell(i);
      cell.setCellStyle(style);
      Object value = values[i];
      if (value instanceof Number number) cell.setCellValue(number.doubleValue());
      else if (value != null)
        cell.setCellValue(value.toString()); // Always STRING, never user-supplied formulas.
    }
  }

  private static class Styles {
    final CellStyle title, header, normal;

    Styles(Workbook book) {
      normal = book.createCellStyle();
      normal.setWrapText(true);
      normal.setVerticalAlignment(VerticalAlignment.CENTER);
      normal.setDataFormat(book.createDataFormat().getFormat("0.###"));
      Font font = book.createFont();
      font.setFontName("맑은 고딕");
      font.setFontHeightInPoints((short) 10);
      normal.setFont(font);
      header = book.createCellStyle();
      header.cloneStyleFrom(normal);
      header.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
      header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      Font bold = book.createFont();
      bold.setFontName("맑은 고딕");
      bold.setBold(true);
      header.setFont(bold);
      title = book.createCellStyle();
      title.cloneStyleFrom(normal);
      Font large = book.createFont();
      large.setFontName("맑은 고딕");
      large.setBold(true);
      large.setFontHeightInPoints((short) 16);
      title.setFont(large);
    }
  }
}
