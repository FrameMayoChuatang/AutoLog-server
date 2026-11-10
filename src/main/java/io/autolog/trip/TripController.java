package io.autolog.trip;

import static io.autolog.trip.TripModels.*;

import io.autolog.auth.Actor;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class TripController {
  private final TripService service;
  private final TripQueryService query;

  public TripController(TripService service, TripQueryService query) {
    this.service = service;
    this.query = query;
  }

  @PutMapping("/device/trips/{id}")
  public Map<String, Object> sync(
      @AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody Sync body) {
    return service.sync(actor, id, body);
  }

  @PostMapping("/device/trips/{id}/confirm")
  public Map<String, Object> confirm(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID id,
      @Valid @RequestBody Details body) {
    return service.confirm(actor, id, body);
  }

  @GetMapping({"/trips/{id}", "/device/trips/{id}"})
  public Map<String, Object> get(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
    return service.get(actor, id);
  }

  @GetMapping("/trips/{id}/audit")
  public List<Map<String, Object>> audit(
      @AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
    return service.audit(actor, id);
  }

  @PatchMapping("/trips/{id}")
  public Map<String, Object> edit(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID id,
      @Valid @RequestBody Details body) {
    return service.edit(actor, id, body);
  }

  @PostMapping("/trips/{id}/approve")
  public Map<String, Object> approve(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID id,
      @Valid @RequestBody Review body) {
    return service.approve(actor, id, body);
  }

  @PostMapping("/trips/{id}/request-changes")
  public Map<String, Object> requestChanges(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID id,
      @Valid @RequestBody Review body) {
    return service.requestChanges(actor, id, body);
  }

  @GetMapping({"/trips", "/device/trips"})
  public Map<String, Object> list(
      @AuthenticationPrincipal Actor actor,
      @RequestParam(required = false) UUID vehicleId,
      @RequestParam(required = false) UUID driverId,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(required = false) State state,
      @RequestParam(required = false) Classification classification,
      @RequestParam(required = false) Approval approval,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return query.list(
        actor, vehicleId, driverId, from, to, state, classification, approval, page, size);
  }

  @GetMapping("/trips/gaps")
  public Map<String, Object> gaps(
      @AuthenticationPrincipal Actor actor,
      @RequestParam String month,
      @RequestParam(required = false) UUID vehicleId) {
    return query.gaps(actor, month, vehicleId);
  }

  @GetMapping("/device/drivers/recent")
  public List<Map<String, Object>> recent(@AuthenticationPrincipal Actor actor) {
    return query.recentDrivers(actor);
  }

  @GetMapping({"/drivers/{id}/recommendations", "/device/drivers/{id}/recommendations"})
  public List<Map<String, Object>> recommendations(
      @AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
    return query.recommendations(actor, id);
  }
}
