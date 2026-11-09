-- Rolls release 2 back, whichever way it was written. In PostgreSQL 18, DROP NOT NULL also drops a named NOT NULL constraint.
ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_public_ref_key;
ALTER TABLE orders ALTER COLUMN public_ref DROP NOT NULL;
CREATE INDEX CONCURRENTLY IF NOT EXISTS orders_status_idx ON orders (status, created_at);
