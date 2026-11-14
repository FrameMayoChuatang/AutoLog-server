-- Align existing driver profiles with automatic approval on registration.
UPDATE drivers SET status = 'ACTIVE' WHERE status = 'PENDING';
