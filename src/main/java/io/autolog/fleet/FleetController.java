package io.autolog.fleet;

import static io.autolog.fleet.FleetModels.*;

import io.autolog.auth.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class FleetController {
  private final FleetService service;

  public FleetController(FleetService service) {
    this.service = service;
  }

  @GetMapping("/vehicles")
  public List<Vehicle> vehicles(@AuthenticationPrincipal Actor actor) {
    return service.vehicles(actor);
  }

  @PostMapping("/vehicles")
  @ResponseStatus(HttpStatus.CREATED)
  public Vehicle createVehicle(
      @AuthenticationPrincipal Actor actor, @Valid @RequestBody FleetRequests.VehicleRequest body) {
    return service.createVehicle(actor, body);
  }

  @GetMapping("/vehicles/{id}")
  public Vehicle getVehicle(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
    return service.vehicle(actor, id);
  }

  @PatchMapping("/vehicles/{id}")
  public Vehicle updateVehicle(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID id,
      @Valid @RequestBody FleetRequests.VehicleRequest body) {
    return service.updateVehicle(actor, id, body);
  }

  @GetMapping("/device/vehicle")
  public Vehicle deviceVehicle(@AuthenticationPrincipal Actor actor) {
    return service.vehicle(actor, actor.vehicleId());
  }

  @GetMapping({"/drivers", "/device/drivers"})
  public List<Driver> drivers(
      @AuthenticationPrincipal Actor actor,
      @RequestParam(defaultValue = "") @Size(max = 80) String q) {
    return service.drivers(actor, q);
  }

  @PostMapping({"/drivers", "/device/drivers"})
  @ResponseStatus(HttpStatus.CREATED)
  public Driver createDriver(
      @AuthenticationPrincipal Actor actor,
      @Valid @RequestBody FleetRequests.DriverCreateRequest body) {
    return service.createDriver(actor, body);
  }

  @PatchMapping("/drivers/{id}")
  public Driver updateDriver(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID id,
      @Valid @RequestBody FleetRequests.DriverUpdateRequest body) {
    return service.updateDriver(actor, id, body);
  }

  @PostMapping("/drivers/{id}/approve")
  public Driver approveDriver(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
    return service.approveDriver(actor, id);
  }

  @PostMapping("/device/drivers/{id}/consent")
  public Driver consent(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID id,
      @Valid @RequestBody FleetRequests.ConsentRequest body) {
    return service.consent(actor, id, body);
  }

  @GetMapping("/drivers/{id}/consents")
  public List<ConsentEvent> consents(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
    return service.consents(actor, id);
  }

  @GetMapping({"/catalog/{kind}", "/device/catalog/{kind}"})
  public List<CatalogItem> catalog(
      @AuthenticationPrincipal Actor actor, @PathVariable String kind) {
    return service.catalog(actor, kind);
  }

  @PostMapping("/catalog/{kind}")
  @ResponseStatus(HttpStatus.CREATED)
  public CatalogItem createCatalog(
      @AuthenticationPrincipal Actor actor,
      @PathVariable String kind,
      @Valid @RequestBody FleetRequests.CatalogRequest body) {
    return service.createCatalog(actor, kind, body);
  }

  @PatchMapping("/catalog/{kind}/{id}")
  public CatalogItem updateCatalog(
      @AuthenticationPrincipal Actor actor,
      @PathVariable String kind,
      @PathVariable UUID id,
      @Valid @RequestBody FleetRequests.CatalogRequest body) {
    return service.updateCatalog(actor, kind, id, body);
  }
}
