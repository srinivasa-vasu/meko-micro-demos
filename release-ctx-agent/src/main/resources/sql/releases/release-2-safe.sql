-- Release 2, rewritten to the zero-downtime migration standard.
-- PostgreSQL 18 adds NOT NULL as a constraint that can be validated separately, under a weaker lock.
ALTER TABLE orders ADD CONSTRAINT orders_public_ref_not_null NOT NULL public_ref NOT VALID;
ALTER TABLE orders VALIDATE CONSTRAINT orders_public_ref_not_null;
-- The unique index is built without blocking writes, then attached as the constraint.
CREATE UNIQUE INDEX CONCURRENTLY orders_public_ref_key ON orders (public_ref);
ALTER TABLE orders ADD CONSTRAINT orders_public_ref_key UNIQUE USING INDEX orders_public_ref_key;
-- An index is dropped without blocking writes. Whether anything still needs it is for the plan check to show.
DROP INDEX CONCURRENTLY orders_status_idx;
