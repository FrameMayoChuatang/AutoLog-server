package io.autolog.trip;

import static io.autolog.trip.TripModels.*;
import static io.autolog.trip.TripRequests.*;

import io.autolog.auth.TokenCodec;
import io.autolog.common.JsonCodec;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class SyncPayloadHasher {
  private final JsonCodec json;

  public SyncPayloadHasher(JsonCodec json) {
    this.json = json;
  }

  public String hash(SyncRequest body) {
    // Preserve the V1 payload layout so pre-upgrade offline retries remain idempotent.
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("sourceRevision", body.sourceRevision());
    payload.put("baseServerVersion", body.baseServerVersion());
    payload.put("driverId", body.driverId());
    payload.put("startedAt", body.startedAt());
    payload.put("endedAt", body.endedAt());
    payload.put("lastObservedAt", body.lastObservedAt());
    payload.put("startOdometerKm", body.startOdometerKm());
    payload.put("endOdometerKm", body.endOdometerKm());
    payload.put("lastOdometerKm", body.lastOdometerKm());
    payload.put("state", body.state());
    if (body.measurementSource() != null)
      payload.put("measurementSource", body.measurementSource());
    if (body.collectionIssues() != null)
      payload.put(
          "collectionIssues", body.collectionIssues().stream().map(Enum::name).sorted().toList());
    return TokenCodec.hash(json.write(payload));
  }
}
