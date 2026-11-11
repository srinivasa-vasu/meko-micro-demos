# Meko × YugabyteDB AMP: Rehearse Before You Release

[![Open in GitHub Codespaces](https://github.com/codespaces/badge.svg)](https://codespaces.new/srinivasa-vasu/meko-micro-demos?quickstart=1) Run it in the cloud: see [In GitHub Codespaces](#in-github-codespaces).

A pull request changes `orders`, the table checkout writes on every purchase. Every line of it looks routine; most of
them would stall checkout in production. A release agent proves the migrations safe **before** the change board sees
them: it forks the production database on **[YugabyteDB AMP](https://www.yugabyte.com/amp/)**, rehearses the
migrations on the fork under checkout's writes, and records its decision. What it knows to check comes from
**[Meko](https://mekodata.ai/)**: a migration standard, a release policy, and what other teams' agents remember.

> **AMP gives the agent a safe place to try the change. Meko tells it what to try, and remembers what it found.**

The agent, the Embabel GOAP planner and the code never change between runs. Only the datapack does.

The app has two use cases, which share the setup (the datapack, the AMP cluster, the organization's shared datapack):

| Use case | Record | What AMP brings | What Meko brings |
|---|---|---|---|
| **Rehearse Before You Release** | `PR-4821` | A zero-copy fork of production to rehearse the migrations on | The standard, the policy, and other teams' incidents and critical queries |
| **Undo a Bad Batch Job** | `INC-3107` | A fork of production **as of a past WAL position**, in seconds | The scheduler's marker with that position, which neither the SRE agent nor the recovery agent knew |

## The story: "Buy online, pick up in store"

Retail is launching store pickup. `orders` has run for years: millions of rows, designed properly from day one. The
pull request, `PR-4821`, adds what pickup needs in two releases (add, then tighten), the way teams ship schema changes:

```sql
-- Release 1: add
ALTER TABLE orders ADD COLUMN fulfillment_type text NOT NULL DEFAULT 'SHIP'
    CHECK (fulfillment_type IN ('SHIP', 'PICKUP'));                         -- 1
ALTER TABLE orders ADD COLUMN public_ref uuid DEFAULT uuidv7();             -- 2
ALTER TABLE orders ADD COLUMN store_id bigint REFERENCES stores (id);       -- 3
CREATE INDEX orders_pickup_idx ON orders (store_id, status) WHERE fulfillment_type = 'PICKUP';  -- 4

-- Release 2: tighten
ALTER TABLE orders ALTER COLUMN public_ref SET NOT NULL;                    -- 5
ALTER TABLE orders ADD CONSTRAINT orders_public_ref_key UNIQUE (public_ref); -- 6
DROP INDEX orders_status_idx;  -- "the new (store_id, status) index covers it"  -- 7
```

What PostgreSQL 18 does with each line, and the safe form the standard asks for:

| # | What actually happens | Safe form |
|---|---|---|
| 1 | The constant default is a catalog change, but the inline `CHECK` scans every row under ACCESS EXCLUSIVE, and the lock request queues behind any long transaction, with every query after it | Add the column, add the `CHECK` `NOT VALID`, `VALIDATE` it; `lock_timeout` and retry throughout |
| 2 | **Rewrites the whole table**: `uuidv7()` (new in PostgreSQL 18) is volatile, so every existing row gets its own value | Add the column with no default, `SET DEFAULT uuidv7()` for new rows, backfill in batches |
| 3 | The inline foreign key validates every row, locking `orders` and `stores` | Add the column, add the key `NOT VALID`, `VALIDATE` it |
| 4 | Blocks writes for the whole index build | `CREATE INDEX CONCURRENTLY` |
| 5 | Scans every row under ACCESS EXCLUSIVE | PostgreSQL 18: `ADD CONSTRAINT … NOT NULL public_ref NOT VALID`, then `VALIDATE` |
| 6 | Builds the unique index while writes wait | `CREATE UNIQUE INDEX CONCURRENTLY`, then `ADD CONSTRAINT … UNIQUE USING INDEX` |
| 7 | The new index holds pickup orders only, so the warehouse dashboard's query loses its index | Keep `orders_status_idx`. Only a plan check of the queries that matter shows it |

The files are in [`src/main/resources/sql/releases/`](src/main/resources/sql/releases): the pull request as written,
rewritten to the standard, and the rollback it ships.

## What AMP brings, what Meko brings

| | One shared dev database | AMP branches only | AMP + Meko |
|---|---|---|---|
| Where the migration is tried | On a copy someone keeps, undone by hand | On a zero-copy fork of `main`, deleted afterwards | The same |
| Production-sized data | Rarely | Always: the fork is `main` | Always |
| The reporting job's long transaction during the rehearsal | Only by luck | Only by luck | Yes: the SRE team's incident is in memory |
| The dropped index (line 7) | Not checked | Not checked: which queries matter? | Checked against the warehouse and checkout teams' critical queries |
| Rollback | Rarely tested | Possible | Rehearsed, because the release policy says so |
| What the change board gets | The pull request description | Logs | A decision record and its evidence, read back from the pull request id |
| What the next team starts with | Nothing | Nothing | The lessons, promoted to shared knowledge |

The middle column is the first rehearsal in the workshop: the same agent with an empty datapack. Everything AMP does
is there; everything that depends on knowing what to check is not.

## The flow

```
 Meko project "meko-amp-workshop"
 ├─ Release team (folder) ── the release team's datapack ─────────────┐
 │    knowledge: migration standard (MIG-01…06), release policy (REL-01…04)
 │    records:   PR-4821's conversation, evidence artifact, lessons
 │    AMP pool:  cluster "checkout" ── main (1,000,000 orders, never changed)
 └─ Org context (shared) (folder) ── the organization's shared datapack ──┐
      memory:    SRE incident, warehouse & checkout critical queries,
                 each written by that team's agent; shared with release management
        │ context_search, both datapacks   │ fork, endpoint, delete
        ▼                                  ▼
  recallContext → reviewMigrations → forkBranch → takeBaseline → rehearseMigrations
        → checkCriticalQueries | noCriticalQueries
        → rehearseRollback     | noRollbackRequired
        → deleteBranch → decide (record + evidence + lessons)
```

Every rehearsal creates its own branch and deletes it:

1. **Recall.** One `context_search` per datapack the agent reads (its own, and the organization's shared one once
   connected) returns the knowledge base and memory side by side: the standard's and the policy's rules, and other
   teams' incidents and critical queries on `orders`. Every fact keeps the datapack it came from and the agent that
   wrote it; memories about other tables are set aside, with the reason. The lock timeout (2s) and the stall limit
   (3 s) are read from the documents' text.
2. **Review.** Each statement is checked against the rules found. Without the standard nothing fires, and the pull
   request is rehearsed as written; with it, the rewritten migrations are rehearsed.
3. **Fork.** The agent checks the account's AMP core budget, forks `main` (`amp_branch_create`, zero-copy) and gives
   the fork a 0.5 vCPU writer endpoint (`amp_compute_create`) that admits only this machine's address.
4. **Rehearse.** Both releases run on the fork while a checkout session inserts an order every 200 ms. If an incident
   names the reporting job, its long transaction runs too. Every result comes from the database itself (below).
5. **Plan check and rollback**, when the context calls for them. The planner picks `checkCriticalQueries` only when
   critical queries are known, and `rehearseRollback` only when the policy requires it; otherwise it takes the skip.
   When a critical query regresses because the release drops the index it used, the agent puts that exact index back
   on the fork, from its definition before the migrations, plans the query again, and drops it once more. "Keep the
   index" is a change only once that brings the query back within the policy's limit; otherwise the release is blocked.
6. **Delete** the branch and its endpoint (`amp_branch_delete`). `main` is never changed.
7. **Decide and record.** The verdict, what blocked it (if anything), the changes it asks for, its reasons and the
   evidence go on `PR-4821`'s record in Meko (a conversation keyed by the pull request id, and an artifact). What the rehearsal taught goes to memory as lessons, which the
   datapack owner can promote to shared knowledge.

## Datapacks, sharing and the project

Each team owns what it knows. The **release team's datapack** holds release management's own context: the standard
and the policy it applies, the AMP cluster, `PR-4821`'s record and the lessons. **Other teams' context** (the platform
SRE team's incident, the warehouse and checkout teams' critical queries) lives in **the organization's shared
datapack**, written by those teams' agents and shared with release management in the Meko app. Reading is enough:
the release agent never writes to it and never talks to those agents. What crosses the team boundary is a share.

On the page, step 5 connects the shared datapack: one shared with you (a facilitator prepares it once and shares it),
or, for a solo run, one created in your own account and filled with the three teams' memory in their names. Creating
is idempotent: an `org-context` datapack you already own is reused, and one that already holds team context is not
filled twice. `MEKO_SHARED_DATAPACK` connects one at start.

Datapacks the app creates are filed in a Meko project, **meko-amp-workshop**, with a folder for each side (a project from an earlier version, still called *Rehearse Before You Release*, is renamed, not duplicated):
*Release team* and *Org context (shared)*. A datapack shared with you stays where its owner filed it.

## Second use case: Undo a Bad Batch Job

An operational anomaly, not an attack: a code defect in a scheduled program. At night the batch scheduler runs
`nightly-reprice` on `orders`. Before every job, its agent records a **marker** in the
organization's shared datapack: the job, a run id, the time, and the WAL position just before it (`pg_current_wal_lsn()`).
When the run commits, an end marker with the same run id records when it finished, the position after it, and the rows
it changed.
Tonight's version ships with a bug in its filter: it marks every paid order pending. Checkout keeps writing. The warehouse pick list goes
empty, and the SRE agent records incident `INC-3107`: what it saw and when. An alert carries a time, not a WAL position,
and AMP forks by position.

The data recovery agent, a different team's, gets the incident id:

- **With only the alert** (the shared datapack is not shared with its team), it has a time and no position. It cannot
  tell the rows the job changed from the orders that were genuinely pending, so it repairs nothing: forking main now
  would copy the damage, and restoring a backup over `orders` would lose everything written since.
- **With the org's shared context**, it finds the run that last finished before the incident was detected (by the
  recorded times, never by the order a search returns them). AMP forks `main` **as of that run's start position**
  (`amp_branch_create` with `fork_point_lsn`). Comparing the fork with `main` gives exactly the damage: the pending
  orders that were paid before the job. Orders already pending before it, and orders created after it, are left alone.
  The damage must account for exactly the rows the run's end marker reports changing; if it does not, the repair is held
  for a person. Before deleting the fork, the agent runs every critical query on `orders` that a team registered in the
  shared datapack, and keeps the answers from before the job. It rehearses the repair on a fresh branch of `main`: the
  repair passes only if it restores exactly the damaged rows, leaves every other pending order alone, and each critical
  query gives back its answer from before the job. Then it repairs `main`, runs the same checks, and
  records the rows it changed on `INC-3107`'s record. The data repair runbook (`REC-01`…`REC-04`, in the shared
  datapack's knowledge base) sets those rules.

This use case changes `main`: the incident step runs the bad job on `orders`, and the recovery puts it back. Branches
are used one at a time (the fork, then the repair rehearsal), so it fits in 1 vCPU with `main`.

Verified on AMP while building it: a fork at a recorded position held exactly the state at that position (the bad
update and the later inserts were absent), and it needed no running compute on `main`. `pg_create_restore_point()` is
not permitted for the `admin` user, so the scheduler records `pg_current_wal_lsn()`; `track_commit_timestamp` is off,
so there is no mapping from a time to a transaction either; and `postgres_fdw` cannot be installed, so the comparison
runs in the application. Forks were tested minutes back; how far back AMP keeps the history to fork from was not
measured.

## What the rehearsal measures

Nothing is estimated. Each value is read from PostgreSQL on the fork:

| Evidence | How |
|---|---|
| Did the statement rewrite the table? | `pg_relation_filenode('orders')` before and after it |
| How much it wrote | `pg_current_wal_lsn()` before and after, as WAL bytes |
| Who waited on a lock | `pg_stat_activity`, sampled every 250 ms: the migration's session, and checkout's |
| What checkout felt | The latency of every checkout insert in flight during the statement (network round trip included) |
| Did a critical query regress? | `EXPLAIN (ANALYZE, BUFFERS)` before and after: buffers read, plan node, index |
| Would keeping a dropped index fix it? | The index put back on the fork from its definition before the migrations, the query planned again, the index dropped again |
| Did the rollback restore the schema? | `orders`' columns, indexes and constraints, by definition, before and after |

### Measured on AMP while building this demo

On an AMP endpoint with 0.5 vCPU, 1,000,000 orders, a client about 250 ms away, and a reporting transaction held for
15 s. Your run measures its own; these are here so you know what to expect.

| | As written | Rewritten to the standard |
|---|---|---|
| Release 1, line 1: waiting for its lock behind the reporting job | 11.3 s, with every checkout insert queued behind it | Gave up after 2 s, retried, done on the third attempt |
| Release 1, line 2: table rewritten | **Yes** (file node changed), 24 s | No |
| Release 1 WAL (DDL only) | **222 MB** | 135 KB (the backfill writes its own) |
| Release 1: checkout's worst insert | **22.8 s** | 2.25 s |
| Release 2, line 5: waiting for its lock | 11.5 s; checkout's worst insert **9.3 s** | Retried twice; checkout's worst insert 2.24 s |
| Warehouse pick-list query after line 7 | **5 → 14,379 buffers** (index scan → parallel sequential scan) | Kept at 5 when `orders_status_idx` stays |

AMP lifecycle, from the same session: a fork took about **3 s**, deleting a branch about **5 s**, and a new branch
endpoint answered within a few minutes (once, the create call ended with a gateway 504 while the endpoint was still
being provisioned; the app adopts that endpoint rather than asking for a second).

## Run

Requirements:

- Java 21+ and Maven
- A Meko account and API key ([cloud.mekodata.ai/signup](https://cloud.mekodata.ai/signup)) with AMP available. The
  rehearsal branch needs 0.5 vCPU of the account's AMP budget on top of `main`'s 0.5 vCPU; a free tier's 0.5 vCPU cap
  is not enough for both.
- This machine's public IP: AMP endpoints admit only listed addresses. The page detects it.

```bash
mvn -q install -DskipTests -pl meko-client,workshop-kit   # once, from the repository root: the shared projects
cd release-ctx-agent
export MEKO_API_KEY=mko_tkn_...
mvn spring-boot:run
open http://localhost:8082
```

No model key is needed: the agent is deterministic.

### In GitHub Codespaces

[Open the repository in Codespaces](https://codespaces.new/srinivasa-vasu/meko-micro-demos?quickstart=1), add
`MEKO_API_KEY` (and `AMP_PASSWORD`, if you like) as Codespaces secrets when asked, then:

```bash
cd release-ctx-agent && mvn spring-boot:run
```

and open the forwarded port 8082. The shared projects are installed when the codespace is created. AMP's endpoints
admit the codespace's public address, which the page detects; after a codespace restarts, its address may differ, and
endpoints created before then admit only the old one.

| Variable | Default | What it does |
|---|---|---|
| `MEKO_API_KEY` | (none) | Meko key: every Meko and AMP call goes through Meko's MCP server |
| `MEKO_DATAPACK_NAME`, `MEKO_DATAPACK_ID` | (none) | Use this datapack instead of choosing one on the page |
| `MEKO_SHARED_DATAPACK` | (none) | Connect this datapack, by name, as the organization's shared context |
| `AMP_PASSWORD` | (none) | The admin password for clusters the app creates, and for choosing an existing one: the page then asks for none |
| `AMP_CLUSTER_ID` | (none) | Use this existing cluster of the datapack |
| `AMP_SEED_ORDERS`, `AMP_SEED_CUSTOMERS` | 1,000,000 / 100,000 | Size of the history seeded on `main` (about a minute at 0.5 vCPU) |

### Credentials

- The **cluster's admin password** is `AMP_PASSWORD` when it is set (the page then asks for none); otherwise one you
  enter on the page, or one AMP generates, which the page shows **once**, as soon as the cluster exists. AMP keeps only
  a verifier, and no tool returns it later. The app holds it **in memory only**: after a restart, enter it again when
  you choose the cluster (or set `AMP_PASSWORD`). Branch endpoints reuse the cluster's password.
- The login user is `admin`, as AMP reports it (`login_user`).
- Endpoints are public on AMP's shared ingress, restricted to the address you give (a /32). AMP refuses an endpoint
  with no allow-list or one wider than /24.

### New endpoints and DNS

A new endpoint's DNS name is published a few seconds after AMP returns its address. Looked up through the operating
system before then, the name is answered "not found", and that answer is kept for minutes: by macOS, and by a VPN's
resolver such as Tailscale's MagicDNS (observed for more than 10 minutes, while public resolvers already had the name). The app therefore looks
new names up with direct DNS queries first, connects only once they find the name, and connects by the address they
returned if the system's cache still says "not found" (`amp/HostResolver.java`). AMP routes endpoints by port, and the
connection's TLS mode does not check the host name.

### Cost and cleanup

A rehearsal holds one branch with a 0.5 vCPU endpoint for a few minutes, then deletes it, also when the run fails.
`main` keeps its 0.5 vCPU endpoint until you delete the cluster in the Meko app. The page shows the account's AMP
budget; the agent checks it before forking and stops with the tier's cap if a branch would exceed it.

## Reading the results

Under the run cards, **Run by run: what the context changed** sets the runs side by side:

- **What each run knew**: the rules it found, the limits it read from them (lock timeout and retries, stall limit,
  regression factor), other teams' incidents and critical queries, what it set aside and why, and what the planner did
  because of it (the reporting job's workload, the plan check, the rollback). What a run knew that the run before it
  did not is marked **+**.
- **Statement by statement**: each statement of the pull request as written, what it met in each run (a table
  rewrite, a stall, a lock wait, a rule's finding, a critical query's plan), and the change the context recommends: the
  statement as rewritten to the standard, or "keep this index", each with where it came from (the standard in a
  datapack's knowledge base, or a team's critical query in its memory). The rewritten migrations mark which statement
  each part replaces (`-- @replaces 2`).
- **Outcome**: each run's verdict, what blocked it, and the changes it asks for.

## Workshop

The page is a hands-on workshop in six steps: choose or create the release team's datapack, create the cluster,
rehearse with no context, add the standard and rehearse, connect the organization's shared datapack and rehearse, then
audit `PR-4821` from its id and promote the lessons.
See [docs/WORKSHOP.md](docs/WORKSHOP.md). A presentation is in [assets/overview.html](assets/overview.html).

## How it works

| Piece | Where |
|---|---|
| The agent: GOAP actions and conditions | `agent/ReleaseRehearsalAgent.java` |
| Rule checks, gated by the rules the knowledge base returned | `agent/MigrationReview.java` |
| Meko reads and writes: recall (`context_search`), the record, evidence, lessons, promotion | `agent/ReleaseMemory.java` |
| The organization's shared datapack, read beside the release team's own | `agent/OrgContext.java`, `api/WorkshopController.java` |
| The recovery agent: GOAP actions and conditions | `agent/RecoveryAgent.java`, `agent/RecoveryModels.java` |
| The incident and the repair, in SQL | `rehearsal/IncidentSql.java` |
| AMP lifecycle over Meko's MCP tools (`amp_*`) | `amp/AmpClient.java` |
| The cluster, seeding `main`, opening and closing rehearsal branches | `amp/AmpDatabase.java` |
| The rehearsal: workloads, statements, evidence, plans, schema | `rehearsal/RehearsalRunner.java` |
| Migration files, with a `-- @backfill` directive for batched backfills | `rehearsal/MigrationScript.java`, `sql/releases/` |
| The standard, the policy, the data repair runbook, and other teams' memory | `kb/`, `context/other-teams.json` |

The critical queries' SQL travels in each memory's metadata, not its text: Meko extracts facts from memory text, which
can rephrase it. For the same reason, the record's turns carry a short tag as their input and the action as their
reasoning, so Meko does not read a record as an instruction from a user.

The knowledge documents are plain prose, one paragraph per rule, with no inline code: Meko's ingestion keeps the words
of a Markdown document but drops inline code and the line breaks between list items. Rules are found by their ids in
the text, whatever the line breaks.

## Cycles, proof and promotion

**Cycles.** Each use case's progress is read from its record in Meko, so a restart of the app changes nothing.
**Start over** (above the steps) writes a new cycle on the record: only runs after it count on the page, and every earlier
run stays on the record for an audit. Running the faulty job starts a new cycle of `INC-3107` by itself. Starting the
release rehearsal over keeps the datapack's documents, so its no-context stage shows as skipped: a genuinely empty first
stage needs a new datapack.

**The proof of a surgical repair.** The faulty job records `orders` by status before and after it, and the recovery
records them after the repair. From before the job to after the repair, each status may change only by what checkout
wrote meanwhile (+200 paid, +100 pending); the page marks each status ✓ or ✗. The agent's own check is the run's row
count: the damage it finds must equal the rows the run's end marker reports changing, or the repair is held.

**Promotion.** The agents write lessons to their own memory, which other agents do not search. **Promote** moves them
into the datapack's shared knowledge base (`memory_promote`), where every agent and person working in it finds them by
search, as they find the runbook. It is one way, and only the datapack's owner can promote. The page lists each lesson
before it is promoted; a lesson repeated by a later run is written once.

## Honest limits

- The rewritten migrations are prepared alongside the pull request, not generated: the agent's part is finding which
  rules the pull request breaks and proving the rewrite on the fork.
- "Keep the index" is proven by putting the index back on the fork after the migrations, which is the release without
  that statement as far as the query's plan goes; the release without it is not rehearsed again end to end.
- The reporting job's transaction is held for 15 s in the rehearsal, not the incident's 40 minutes: enough to show the
  queue, short enough for a workshop.
- Checkout latency includes the round trip from this machine to AMP; compare runs from the same place.
- Recovery: the comparison takes the run that last finished before the incident, by the times the scheduler recorded.
  Runs recorded before runs had ids (no end marker) are used only when no completed run qualifies, and their damage
  cannot be checked against a row count.
- Recovery: the comparison loads the pending orders' ids into the application (thousands of rows here); for millions it
  would run as a batched job.
- Measured on PostgreSQL 18.2 on AMP's `postgres` engine. That engine's extension catalog did not include pgvector, AGE
  or DocumentDB when this was built; this demo needs none of them.

## Layout

```
src/main/java/meko/agentic/release/
  agent/       the release agent, its review, its Meko reads and writes
  amp/         AMP over Meko's MCP tools, the cluster and its branches
  rehearsal/   the rehearsal runner and migration files
  run/         background jobs, planner events
  api/         the workshop page's API
src/main/resources/
  sql/         schema, seed, and the pull request's migrations (as written, rewritten, rollback)
  kb/          the migration standard and the release policy
  context/     other teams' memory
  static/      the workshop page
docs/WORKSHOP.md, assets/overview.html
```
