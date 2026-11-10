CREATE TABLE companies (
 id uuid PRIMARY KEY, name varchar(120) NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE admins (
 id uuid PRIMARY KEY, company_id uuid NOT NULL REFERENCES companies(id),
 email varchar(254) NOT NULL UNIQUE CHECK(email = lower(email)), name varchar(80) NOT NULL,
 password_hash varchar(100), role varchar(10) NOT NULL CHECK(role IN ('OWNER','STAFF')),
 active boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(company_id,id)
);
CREATE UNIQUE INDEX one_owner_per_company ON admins(company_id) WHERE role = 'OWNER';
CREATE TABLE vehicles (
 id uuid PRIMARY KEY, company_id uuid NOT NULL REFERENCES companies(id),
 registration_number varchar(30) NOT NULL, model varchar(100) NOT NULL,
 active boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(company_id,id), UNIQUE(company_id,registration_number)
);
CREATE TABLE credentials (
 id uuid PRIMARY KEY, company_id uuid NOT NULL REFERENCES companies(id),
 admin_id uuid, vehicle_id uuid, token_hash char(64) NOT NULL UNIQUE,
 kind varchar(20) NOT NULL CHECK(kind IN ('ADMIN','DEVICE','SETUP','PAIRING')),
 expires_at timestamptz NOT NULL, revoked_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(company_id,admin_id) REFERENCES admins(company_id,id),
 FOREIGN KEY(company_id,vehicle_id) REFERENCES vehicles(company_id,id),
 CHECK((kind IN ('ADMIN','SETUP') AND admin_id IS NOT NULL AND vehicle_id IS NULL)
    OR (kind IN ('DEVICE','PAIRING') AND vehicle_id IS NOT NULL AND admin_id IS NULL))
);
CREATE INDEX credentials_expiry_idx ON credentials(expires_at);
CREATE TABLE drivers (
 id uuid PRIMARY KEY, company_id uuid NOT NULL REFERENCES companies(id),
 name varchar(80) NOT NULL, department varchar(100) NOT NULL,
 status varchar(12) NOT NULL CHECK(status IN ('PENDING','ACTIVE','INACTIVE')),
 location_consent boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(company_id,id)
);
CREATE INDEX drivers_company_name_idx ON drivers(company_id,name);
CREATE TABLE consent_events (
 id uuid PRIMARY KEY, company_id uuid NOT NULL REFERENCES companies(id), driver_id uuid NOT NULL,
 vehicle_id uuid NOT NULL, granted boolean NOT NULL, notice_version varchar(80) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(company_id,driver_id) REFERENCES drivers(company_id,id),
 FOREIGN KEY(company_id,vehicle_id) REFERENCES vehicles(company_id,id)
);
CREATE TABLE clients (
 id uuid PRIMARY KEY, company_id uuid NOT NULL REFERENCES companies(id), name varchar(120) NOT NULL,
 address varchar(300), active boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(company_id,id), UNIQUE(company_id,name)
);
CREATE TABLE purposes (
 id uuid PRIMARY KEY, company_id uuid NOT NULL REFERENCES companies(id), name varchar(120) NOT NULL,
 active boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(company_id,id), UNIQUE(company_id,name)
);
