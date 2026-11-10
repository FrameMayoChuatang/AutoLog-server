package io.autolog.trip;

import static io.autolog.trip.TripModels.*;
import static io.autolog.trip.TripQueryModels.*;
import static io.autolog.trip.TripRequests.*;

import io.autolog.auth.Actor;
import io.autolog.common.PageResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class TripController {
  private final TripSyncService sync;
  private final TripReviewService review;
  private final TripQueryService query;

  public TripController(TripSyncService sync, TripReviewService review, TripQueryService query) {
    this.sync = sync;
    this.review = review;
    this.query = query;
  }

  @PutMapping("/device/trips/{id}")
  public TripResponse sync(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID id,
      @Valid @RequestBody SyncRequest body) {
    return sync.sync(actor, id, body);
  }

  @PostMapping("/device/trips/{id}/confirm")
  public TripResponse confirm(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID id,
      @Valid @RequestBody DetailsRequest body) {
    return review.confirm(actor, id, body);
  }

  @PatchMapping("/device/trips/{id}")
  public TripResponse editByDriver(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID id,
      @Valid @RequestBody DetailsRequest body) {
    return review.editByDriver(actor, id, body);
  }

  @GetMapping({"/trips/{id}", "/device/trips/{id}"})
  public TripResponse get(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
    return query.get(actor, id);
  }

  @GetMapping("/trips/{id}/audit")
  public List<TripAuditRepository.AuditEvent> audit(
      @AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
    return query.audit(actor, id);
  }

  @PatchMapping("/trips/{id}")
  public TripResponse edit(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID id,
      @Valid @RequestBody DetailsRequest body) {
    return review.edit(actor, id, body);
  }

  @PostMapping("/trips/{id}/approve")
  public TripResponse approve(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID id,
      @Valid @RequestBody ReviewRequest body) {
    return review.approve(actor, id, body);
  }

  @PostMapping("/trips/{id}/request-changes")
  public TripResponse requestChanges(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID id,
      @Valid @RequestBody ReviewRequest body) {
    return review.requestChanges(actor, id, body);
  }

  @GetMapping({"/trips", "/device/trips"})
  public PageResponse<TripResponse> list(
      @AuthenticationPrincipal Actor actor,
      @RequestParam(required = false) UUID vehicleId,
      @RequestParam(required = false) UUID driverId,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(required = false) State state,
      @RequestParam(required = false) Classification classification,
      @RequestParam(required = false) Approval approval,
      @RequestParam(required = false) Boolean confirmed,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return query.list(
        actor,
        new Filter(
            vehicleId, driverId, from, to, state, classification, approval, confirmed, page, size));
  }

  @GetMapping("/trips/gaps")
  public GapReport gaps(
      @AuthenticationPrincipal Actor actor,
      @RequestParam String month,
      @RequestParam(required = false) UUID vehicleId) {
    return query.gaps(actor, month, vehicleId);
  }

  @GetMapping("/device/drivers/recent")
  public List<RecentDriver> recent(@AuthenticationPrincipal Actor actor) {
    return query.recentDrivers(actor);
  }

  @GetMapping({"/drivers/{id}/recommendations", "/device/drivers/{id}/recommendations"})
  public List<Recommendation> recommendations(
      @AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
    return query.recommendations(actor, id);
  }
}
