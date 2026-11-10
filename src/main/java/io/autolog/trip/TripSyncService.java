package io.autolog.trip;

import static io.autolog.trip.TripAuditRepository.Operation.*;
import static io.autolog.trip.TripModels.*;
import static io.autolog.trip.TripRequests.*;

import io.autolog.auth.Actor;
import io.autolog.common.ApiException;
import io.autolog.fleet.FleetRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TripSyncService {
  private final TripRepository trips;
  private final FleetRepository fleet;
  private final TripAuditRepository audit;
  private final SyncPayloadHasher hasher;

  public TripSyncService(
      TripRepository trips,
      FleetRepository fleet,
      TripAuditRepository audit,
      SyncPayloadHasher hasher) {
    this.trips = trips;
    this.fleet = fleet;
    this.audit = audit;
    this.hasher = hasher;
  }

  @Transactional
  public TripResponse sync(Actor actor, UUID id, SyncRequest body) {
    actor.requireDevice();
    TripSyncPolicy.validate(body);
    // Keep vehicle -> trip lock order shared with approval, including first uploads.
    if (!fleet.vehicle(actor.companyId(), actor.vehicleId(), true).active())
      throw ApiException.denied();
    var existing = trips.lockDeviceTrip(actor, id);
    String hash = hasher.hash(body);
    if (existing.isPresent()) {
      var before = existing.get();
      if (body.sourceRevision() == before.sourceRevision()
          && hash.equals(before.sourcePayloadHash())) return TripResponse.replay(before);
      TripSyncPolicy.validateProgress(before, body);
      validateDriver(actor, body.driverId());
      trips.updateMeasurements(actor, id, body, hash);
      trips.updateEvidence(actor, id, body);
      return audit.record(actor, id, DEVICE_SYNC, null, before);
    }
    if (body.baseServerVersion() != 0)
      throw ApiException.conflict("신규 운행의 baseServerVersion은 0이어야 합니다.");
    validateDriver(actor, body.driverId());
    trips.insert(actor, id, body, hash);
    trips.updateEvidence(actor, id, body);
    return audit.record(actor, id, DEVICE_SYNC, null, null);
  }

  private void validateDriver(Actor actor, UUID driver) {
    if (driver != null) fleet.shareDriver(actor.companyId(), driver);
  }
}
