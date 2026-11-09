-- Release 1, rewritten to the zero-downtime migration standard. Every statement runs with a lock timeout and is retried.
-- A constant default is a catalog change only; the CHECK is added unvalidated, then validated under a weaker lock.
ALTER TABLE orders ADD COLUMN fulfillment_type text NOT NULL DEFAULT 'SHIP';
ALTER TABLE orders ADD CONSTRAINT orders_fulfillment_type_check CHECK (fulfillment_type IN ('SHIP', 'PICKUP')) NOT VALID;
ALTER TABLE orders VALIDATE CONSTRAINT orders_fulfillment_type_check;
-- uuidv7() is volatile: as an ADD COLUMN default it rewrites the table. The default is set for new rows only.
ALTER TABLE orders ADD COLUMN public_ref uuid;
ALTER TABLE orders ALTER COLUMN public_ref SET DEFAULT uuidv7();
-- @backfill orders.public_ref = uuidv7() batch 20000
ALTER TABLE orders ADD COLUMN store_id bigint;
ALTER TABLE orders ADD CONSTRAINT orders_store_id_fkey FOREIGN KEY (store_id) REFERENCES stores (id) NOT VALID;
ALTER TABLE orders VALIDATE CONSTRAINT orders_store_id_fkey;
CREATE INDEX CONCURRENTLY orders_pickup_idx ON orders (store_id, status) WHERE fulfillment_type = 'PICKUP';
