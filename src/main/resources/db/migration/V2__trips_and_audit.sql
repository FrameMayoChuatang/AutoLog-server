CREATE TABLE trips (
 id uuid PRIMARY KEY, company_id uuid NOT NULL REFERENCES companies(id), vehicle_id uuid NOT NULL, driver_id uuid,
 started_at timestamptz NOT NULL, ended_at timestamptz, last_observed_at timestamptz,
 start_odometer_km numeric(14,3), end_odometer_km numeric(14,3), last_odometer_km numeric(14,3),
 state varchar(16) NOT NULL CHECK(state IN ('RUNNING','COMPLETED','INTERRUPTED')),
 classification varchar(16) NOT NULL DEFAULT 'UNCONFIRMED' CHECK(classification IN ('UNCONFIRMED','COMMUTE','BUSINESS','PERSONAL')),
 purpose_id uuid, client_id uuid, purpose_text varchar(200), client_text varchar(200), note varchar(1000),
 driver_name varchar(80), driver_department varchar(100), driver_confirmed_at timestamptz,
 approval_status varchar(24) NOT NULL DEFAULT 'NOT_READY' CHECK(approval_status IN ('NOT_READY','PENDING','APPROVED','CHANGES_REQUESTED')),
 approved_by uuid, approved_at timestamptz,
 source_revision bigint NOT NULL CHECK(source_revision>0), source_payload_hash char(64) NOT NULL,
 server_version bigint NOT NULL DEFAULT 1 CHECK(server_version>0),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(company_id,id),
 FOREIGN KEY(company_id,vehicle_id) REFERENCES vehicles(company_id,id),
 FOREIGN KEY(company_id,driver_id) REFERENCES drivers(company_id,id),
 FOREIGN KEY(company_id,purpose_id) REFERENCES purposes(company_id,id),
 FOREIGN KEY(company_id,client_id) REFERENCES clients(company_id,id),
 FOREIGN KEY(company_id,approved_by) REFERENCES admins(company_id,id),
 CHECK(start_odometer_km>=0 AND end_odometer_km>=0 AND last_odometer_km>=0),
 CHECK(ended_at IS NULL OR ended_at>=started_at),
 CHECK(last_observed_at IS NULL OR last_observed_at>=started_at),
 CHECK(state<>'RUNNING' OR ended_at IS NULL),
 CHECK(state<>'COMPLETED' OR ended_at IS NOT NULL),
 CHECK(approval_status<>'APPROVED' OR (driver_confirmed_at IS NOT NULL AND approved_by IS NOT NULL AND approved_at IS NOT NULL AND state='COMPLETED'))
);
CREATE INDEX trips_company_started_idx ON trips(company_id,started_at,id);
CREATE INDEX trips_vehicle_started_idx ON trips(company_id,vehicle_id,started_at,id);
CREATE INDEX trips_driver_idx ON trips(company_id,driver_id,started_at DESC);
CREATE INDEX trips_review_idx ON trips(company_id,approval_status,started_at);
CREATE TABLE trip_audit (
 id uuid PRIMARY KEY, company_id uuid NOT NULL, trip_id uuid NOT NULL,
 server_version bigint NOT NULL, actor_admin_id uuid, actor_vehicle_id uuid,
 operation varchar(40) NOT NULL, reason varchar(1000), before_value jsonb, after_value jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(company_id,trip_id) REFERENCES trips(company_id,id),
 FOREIGN KEY(company_id,actor_admin_id) REFERENCES admins(company_id,id),
 FOREIGN KEY(company_id,actor_vehicle_id) REFERENCES vehicles(company_id,id),
 UNIQUE(trip_id,server_version),
 CHECK((actor_admin_id IS NOT NULL AND actor_vehicle_id IS NULL) OR (actor_admin_id IS NULL AND actor_vehicle_id IS NOT NULL))
);
