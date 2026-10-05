# Service map, dependencies and ownership

Read this before scoping an investigation. Knowing which services sit behind a symptom is what
lets an investigation read three services' logs instead of twenty.

## Checkout path

```
checkout  ->  payment-client  ->  payment-api  ->  external provider
   |
   +-------->  cart, pricing, tax
```

- **checkout** — owns the order flow. Surfaces failures from everything downstream, which makes
  it a poor place to diagnose and a good place to detect.
- **payment-client** — the library and sidecar that calls payment-api. **Owns the upstream
  timeout configuration**, which is where several checkout incidents have actually originated.
- **payment-api** — our gateway to the external provider. Its p95 is the number to compare a
  timeout against; the provider's published latency is not.

A checkout symptom almost always needs checkout, payment-client and payment-api together, and
almost never needs cart, pricing or tax unless the error explicitly names them.

## Search path

```
indexer  ->  search index  ->  search
   ^
   +--  catalog (source of truth)
```

- **indexer** — scheduled rebuilds and incremental updates. Writes segments and commits them.
- **search** — reads whatever generation was last committed. Stale results are usually an
  indexer problem surfacing here.
- **catalog** — source of truth. If catalog and search disagree, search is wrong.

## Deployment coupling

`payment-service` releases change `payment-client` behaviour without a `payment-client` deploy.
This is the single most common source of confusion during checkout incidents: the change log for
payment-client shows nothing, because the change arrived through payment-service.

Always check `payment-service` deploys when investigating checkout, even though it is not in the
request path under that name.

## Escalation

- checkout, cart, pricing → Commerce on-call
- payment-client, payment-api → Payments on-call
- search, indexer, catalog → Discovery on-call

Page the owning team for the **cause**, not the symptom. Paging Commerce for a payment timeout
costs twenty minutes of handover.
