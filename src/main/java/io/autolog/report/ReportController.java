package io.autolog.report;

import io.autolog.auth.Actor;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ReportController {
  private final ReportService service;

  public ReportController(ReportService service) {
    this.service = service;
  }

  @GetMapping("/statistics")
  public ReportModels.Statistics stats(
      @AuthenticationPrincipal Actor actor,
      @RequestParam String month,
      @RequestParam(required = false) UUID vehicleId) {
    return service.stats(actor, month, vehicleId);
  }

  @GetMapping("/reports/monthly.xlsx")
  public ResponseEntity<byte[]> monthly(
      @AuthenticationPrincipal Actor actor,
      @RequestParam String month,
      @RequestParam(required = false) UUID vehicleId) {
    return download(
        service.workbook(actor, month, vehicleId, false), "autolog-monthly-" + month + ".xlsx");
  }

  @GetMapping("/reports/nts-draft.xlsx")
  public ResponseEntity<byte[]> nts(
      @AuthenticationPrincipal Actor actor,
      @RequestParam String month,
      @RequestParam(required = false) UUID vehicleId) {
    return download(
        service.workbook(actor, month, vehicleId, true), "autolog-nts-draft-" + month + ".xlsx");
  }

  private ResponseEntity<byte[]> download(byte[] bytes, String name) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(name).build().toString())
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(bytes);
  }
}
