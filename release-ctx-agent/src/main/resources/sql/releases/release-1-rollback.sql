-- Rolls release 1 back, whichever way it was written. Dropping a column is a catalog change; the index goes without blocking writes.
DROP INDEX CONCURRENTLY IF EXISTS orders_pickup_idx;
ALTER TABLE orders DROP COLUMN IF EXISTS store_id;
ALTER TABLE orders DROP COLUMN IF EXISTS public_ref;
ALTER TABLE orders DROP COLUMN IF EXISTS fulfillment_type;
