# Zero-downtime migration standard (PostgreSQL 18)

Owner: platform data team. Applies to every schema migration on a table that serves live traffic.

A migration that holds or waits for a strong lock on a busy table stalls every query that arrives after it. A statement that waits for an ACCESS EXCLUSIVE lock blocks the reads and writes queued behind it, even before it runs. The rules below keep migrations off that path. Each rule names the statements it applies to and the safe form.

MIG-01 Lock timeout. Every migration session sets lock_timeout to 2s and retries a statement cancelled by it (SQLSTATE 55P03), up to 10 attempts. A statement then waits for its lock for at most 2 seconds, so a long-running transaction cannot turn one migration into an outage. Concurrent index builds are the exception: they wait without blocking writers, and a cancelled one leaves an invalid index, so they run without the timeout.

MIG-02 Volatile defaults. Never add a column with a DEFAULT that calls a volatile function, such as uuidv7(), gen_random_uuid(), random() or clock_timestamp(). Every existing row gets its own value, so PostgreSQL rewrites the whole table under ACCESS EXCLUSIVE. Add the column without a default, then ALTER COLUMN SET DEFAULT for new rows only, then backfill the existing rows in batches by primary key. A constant default is a catalog change and is allowed.

MIG-03 Constraints on existing rows. A CHECK constraint, a FOREIGN KEY or a NOT NULL on a table with rows is added NOT VALID and then checked with VALIDATE CONSTRAINT, which takes a SHARE UPDATE EXCLUSIVE lock and lets writes continue. This includes constraints declared inline in ADD COLUMN, and SET NOT NULL: in PostgreSQL 18, add NOT NULL as a named constraint with NOT VALID, then validate it.

MIG-04 Indexes. Build indexes with CREATE INDEX CONCURRENTLY. A unique constraint is built as CREATE UNIQUE INDEX CONCURRENTLY and attached with ADD CONSTRAINT UNIQUE USING INDEX. A concurrent build cannot run inside a transaction block: the migration tool must run it on its own.

MIG-05 Dropping an index. Drop an index with DROP INDEX CONCURRENTLY, and only after a plan check of every registered critical query on the table shows that no query depends on it. With the skip scan in PostgreSQL 18, a composite index may or may not serve a query that does not lead with its first column: check, do not assume.

MIG-06 Type changes. A column type change that rewrites the table, for example integer to bigint, is done as a new column, a sync trigger, a batched backfill and a swap in a short lock window, never as one ALTER COLUMN TYPE.
