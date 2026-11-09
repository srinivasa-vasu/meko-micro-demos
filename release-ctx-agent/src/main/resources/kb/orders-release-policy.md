# Release policy: the orders table

Owner: release management. Applies to every pull request that changes the schema of the orders table, which checkout writes on every purchase.

REL-01 Rehearsal. Every migration on orders is rehearsed on an AMP branch of main before it is approved, with the checkout workload running and with every long-running workload on orders that an incident record names. The main branch is never changed by a rehearsal.

REL-02 Rollback. The rollback the pull request ships is rehearsed on the same branch, after the migration, and must restore the orders schema exactly: the same columns, indexes and constraints as before.

REL-03 Approval. A release is approved only if no checkout insert stalls for more than 3 s during the rehearsal, every statement completes, and no registered critical query on orders reads more than 10 times the buffers it read before the migration.

REL-04 Record. The rehearsal's evidence and the decision are recorded on the pull request's record, so the change board can read them back from the pull request id alone. A lesson the rehearsal teaches is written to memory.
