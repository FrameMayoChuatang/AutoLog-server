package io.autolog.report;

import io.autolog.common.JdbcExecutor;
import io.autolog.trip.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class ReportRepository {
  private final JdbcExecutor db;
  private final TripRepository trips;

  public ReportRepository(JdbcExecutor db, TripRepository trips) {
    this.db = db;
    this.trips = trips;
  }

  public String companyName(UUID company) {
    return db.one("SELECT name FROM companies WHERE id=?", (r, n) -> r.getString("name"), company);
  }

  public Optional<Trip> latestApproved(UUID company, UUID vehicle, Instant boundary) {
    return db.optional(
        """
        SELECT *
        FROM trips
        WHERE company_id=?
        AND vehicle_id=?
        AND approval_status='APPROVED'
        AND ended_at<=?
        ORDER BY ended_at DESC, id DESC LIMIT 1
        """,
        trips.mapper(),
        company,
        vehicle,
        boundary);
  }
}
