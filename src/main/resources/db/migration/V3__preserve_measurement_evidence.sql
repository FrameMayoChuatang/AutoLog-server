ALTER TABLE trips ADD COLUMN measurement_locked_at timestamptz;
ALTER TABLE trips ADD COLUMN measurement_source varchar(40);
ALTER TABLE trips ADD COLUMN collection_issues jsonb NOT NULL DEFAULT '[]'::jsonb
  CHECK(jsonb_typeof(collection_issues)='array');

-- Approval/confirmation may have been withdrawn. The first confirmation still seals its evidence.
UPDATE trips t SET measurement_locked_at=COALESCE(t.driver_confirmed_at,
  (SELECT min(a.created_at) FROM trip_audit a WHERE a.company_id=t.company_id AND a.trip_id=t.id AND a.operation='DRIVER_CONFIRM'));
ALTER TABLE trips ADD CONSTRAINT confirmed_measurement_locked
  CHECK(driver_confirmed_at IS NULL OR measurement_locked_at IS NOT NULL);
