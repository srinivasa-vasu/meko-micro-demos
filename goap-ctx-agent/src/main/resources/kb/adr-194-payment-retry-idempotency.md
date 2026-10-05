# ADR-194: Payment retries are idempotent, keyed by the authorization id

Fictional architecture decision record for the Meko GOAP demo. Status: accepted. Owner: Payments architecture.

## ADR-194 context

Incident INC-1877: a retry re-captured a payment that had already settled, and the customer was charged twice. The
retry path had no idempotency key, so the second capture looked like a new one.

## ADR-194 decision

Payment retries must be idempotent and keyed by the original authorization id. A retry that finds a settled capture
for that authorization id must stop, not capture again.

## ADR-194 consequences

- Any change to the retry contract requires an architecture review.
- Retry-path changes are reviewed against INC-1877: a retry must never produce a second capture.
