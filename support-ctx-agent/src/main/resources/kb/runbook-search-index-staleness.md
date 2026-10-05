# Runbook: search returning stale documents after an index rebuild

**Services:** search, indexer, catalog
**Typical severity:** SEV3, SEV2 if stale results are being shown on revenue pages

## Recognising this class of incident

Search returns documents that no longer match the catalogue — deleted items still listed,
price or stock changes missing, recently added items absent. Query latency is usually *normal*,
which is the tell that separates this from a capacity problem: the index is serving quickly, it
is simply serving the wrong generation.

Check `search.index_lag` first. Under a minute is healthy. Tens of minutes with normal latency
means the indexer is writing segments that are never being committed, so search keeps reading
the last good generation.

This is almost never a search-service fault. Search is the symptom; the indexer is the cause.

## First checks, in order

1. **Is the indexer still running, or hung?** A hung indexer holds the rebuild lock, so the
   next scheduled run cannot start either. A stalled job and a crashed job look identical from
   search's side but need opposite responses.
2. **Did the rebuild commit?** Look for `documents_committed` advancing. New segments written
   with no commit is the signature of this incident: the work happened, the swap did not.
3. **Is it the whole index or one shard?** Partial staleness points at a single indexer worker,
   which is a much smaller blast radius and does not need a full rebuild.
4. **Was anything deployed?** If nothing shipped to search or indexer in 72 hours, this is a job
   failure, not a regression, and rolling back will achieve nothing.

## Remediation

Prefer the smallest action that commits the pending work:

1. **Force a commit or flush** on the indexer. If the segments are on disk and only the swap is
   missing, this restores correct results in seconds with no reindex.
2. **Restart the indexer** if it is hung and unresponsive to a flush. Restarting releases the
   rebuild lock; the next scheduled run then proceeds normally.
3. **Re-trigger the rebuild** only if the segments are corrupt or incomplete. This is expensive
   and takes the index lag *up* before it comes down, so it is a last resort rather than a
   first instinct.

Do not roll back the search service. Search is reading correctly from what it was given; a
rollback changes nothing and costs a deploy cycle.

## What this incident class costs when handled badly

Reaching for a full reindex first. It is the most visible action and the slowest fix: a rebuild
on the full catalogue takes far longer than a flush, and during it search is *more* stale, not
less. The cheap check — did the commit happen — takes seconds and resolves most occurrences.

## Why there is rarely history for this

Search indexing incidents are infrequent compared with payment and checkout regressions, so an
incident corpus weighted towards checkout will have nothing relevant to offer here. That is the
correct outcome and not a failure of recall: this runbook is the context that applies, and a
reuse gate that invented a checkout match for a search incident would be worse than one that
admits it knows nothing.
