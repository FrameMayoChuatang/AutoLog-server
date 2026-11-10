package io.autolog.fleet;

import static io.autolog.fleet.FleetModels.*;

import io.autolog.auth.Actor;
import io.autolog.common.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FleetService {
  private final FleetRepository repository;
  private final io.autolog.auth.CredentialRepository credentials;

  public FleetService(
      FleetRepository repository, io.autolog.auth.CredentialRepository credentials) {
    this.repository = repository;
    this.credentials = credentials;
  }

  public List<Vehicle> vehicles(Actor actor) {
    return repository.vehicles(actor.companyId());
  }

  public Vehicle vehicle(Actor actor, UUID id) {
    return repository.vehicle(actor.companyId(), id, false);
  }

  @Transactional
  public Vehicle createVehicle(Actor actor, FleetRequests.VehicleRequest body) {
    var id = UUID.randomUUID();
    repository.createVehicle(actor.companyId(), id, body);
    return vehicle(actor, id);
  }

  @Transactional
  public Vehicle updateVehicle(Actor actor, UUID id, FleetRequests.VehicleRequest body) {
    repository.vehicle(actor.companyId(), id, true);
    repository.updateVehicle(actor.companyId(), id, body);
    if (!body.active()) credentials.revokeVehicle(actor.companyId(), id);
    return vehicle(actor, id);
  }

  public List<Driver> drivers(Actor actor, String query) {
    if (query.length() > 80) throw ApiException.bad("검색어가 너무 깁니다.");
    return repository.drivers(actor.companyId(), query, actor.isDevice());
  }

  @Transactional
  public Driver createDriver(Actor actor, FleetRequests.DriverCreateRequest body) {
    var id = UUID.randomUUID();
    repository.createDriver(
        actor.companyId(), id, body, actor.isDevice() ? DriverStatus.PENDING : DriverStatus.ACTIVE);
    return repository.driver(actor.companyId(), id, false);
  }

  @Transactional
  public Driver updateDriver(Actor actor, UUID id, FleetRequests.DriverUpdateRequest body) {
    repository.driver(actor.companyId(), id, true);
    repository.updateDriver(actor.companyId(), id, body);
    return repository.driver(actor.companyId(), id, false);
  }

  @Transactional
  public Driver approveDriver(Actor actor, UUID id) {
    var driver = repository.driver(actor.companyId(), id, true);
    if (driver.status() != DriverStatus.PENDING)
      throw ApiException.conflict("등록 대기 운전자만 승인할 수 있습니다.");
    repository.activateDriver(actor.companyId(), id);
    return repository.driver(actor.companyId(), id, false);
  }

  @Transactional
  public Driver consent(Actor actor, UUID id, FleetRequests.ConsentRequest body) {
    var driver = repository.driver(actor.companyId(), id, true);
    if (body.granted() && driver.status() == DriverStatus.INACTIVE)
      throw ApiException.conflict("비활성 운전자입니다.");
    repository.updateConsent(actor.companyId(), id, actor.vehicleId(), body);
    return repository.driver(actor.companyId(), id, false);
  }

  public List<ConsentEvent> consents(Actor actor, UUID id) {
    repository.driver(actor.companyId(), id, false);
    return repository.consents(actor.companyId(), id);
  }

  public List<CatalogItem> catalog(Actor actor, String kind) {
    return repository.catalog(actor.companyId(), CatalogKind.from(kind), actor.isDevice());
  }

  private CatalogKind validateCatalog(String value, FleetRequests.CatalogRequest body) {
    var kind = CatalogKind.from(value);
    if (kind == CatalogKind.PURPOSES && (body.address() != null || body.description() != null))
      throw ApiException.bad("목적에는 주소·거래처 설명을 지정할 수 없습니다.");
    return kind;
  }

  @Transactional
  public CatalogItem createCatalog(Actor actor, String kind, FleetRequests.CatalogRequest body) {
    var selected = validateCatalog(kind, body);
    var id = UUID.randomUUID();
    repository.createCatalog(actor.companyId(), selected, id, body);
    return repository.catalogItem(actor.companyId(), selected, id, false);
  }

  @Transactional
  public CatalogItem updateCatalog(
      Actor actor, String kind, UUID id, FleetRequests.CatalogRequest body) {
    var selected = CatalogKind.from(kind);
    repository.catalogItem(actor.companyId(), selected, id, true);
    validateCatalog(kind, body);
    repository.updateCatalog(actor.companyId(), selected, id, body);
    return repository.catalogItem(actor.companyId(), selected, id, false);
  }
}
