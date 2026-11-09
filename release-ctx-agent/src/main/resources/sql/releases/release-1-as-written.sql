-- Release 1, "Buy online, pick up in store": the pull request as the developer wrote it.
ALTER TABLE orders ADD COLUMN fulfillment_type text NOT NULL DEFAULT 'SHIP'
    CHECK (fulfillment_type IN ('SHIP', 'PICKUP'));
ALTER TABLE orders ADD COLUMN public_ref uuid DEFAULT uuidv7();
ALTER TABLE orders ADD COLUMN store_id bigint REFERENCES stores (id);
CREATE INDEX orders_pickup_idx ON orders (store_id, status) WHERE fulfillment_type = 'PICKUP';
