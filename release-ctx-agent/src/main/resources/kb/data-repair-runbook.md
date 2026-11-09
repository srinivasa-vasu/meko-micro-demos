# Data repair runbook

Owner: platform data team. Applies to repairing rows that a job or a release changed by mistake, on any table that serves live traffic.

A bad job is rarely alone on its table: checkout keeps writing while it runs and after it. A repair that guesses which rows to fix, or restores a backup over the live table, destroys good data to fix bad data. The rules below repair exactly the rows the job changed, and nothing else.

REC-01 Point in time. Find the WAL position from just before the job started: every run of a scheduled job writes a start marker with its run id, its start time and that position, and an end marker with the same run id once it committed. Take the run that last finished before the incident was detected, by the recorded times. Fork the database at that position on a branch, and compare. Never restore a backup over the live table to undo one job.

REC-02 Exact rows. Repair only the rows whose value the job changed: rows that differ between the fork and the live table, still hold the value the job wrote, and existed before the job. Rows created after the job, and rows that were already in that state before it, are left alone. The damage found must account for exactly the rows the run's end marker reports changing; if it does not, hold the repair for a person.

REC-03 Rehearse the repair. Run the repair on a fresh branch of the live database first, and check that it restores exactly the damaged rows and that the critical queries on the table return what they should. Then run the same repair on the live database.

REC-04 Record. The repaired rows, the position the comparison used, and the checks are recorded on the incident's record, so anyone can read back what was changed and on what evidence.
