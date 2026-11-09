-- Release 2: tighten what release 1 added, and tidy up an index that looks redundant.
ALTER TABLE orders ALTER COLUMN public_ref SET NOT NULL;
ALTER TABLE orders ADD CONSTRAINT orders_public_ref_key UNIQUE (public_ref);
DROP INDEX orders_status_idx;
