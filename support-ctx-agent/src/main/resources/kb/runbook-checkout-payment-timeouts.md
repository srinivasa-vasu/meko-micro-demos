# Runbook: checkout failures after a payment-service deployment

**Services:** checkout, payment-client, payment-api
**Typical severity:** SEV2, SEV1 if the checkout error rate is above 15%

## Recognising this class of incident

Checkout error rate climbs within minutes of a payment-service release, while the payment
provider itself reports normal availability. The tell is the *shape*: errors track deploy time,
not traffic volume. If errors scale with traffic instead, this is not the right runbook —
look at capacity, not configuration.

The failure is usually self-inflicted. The payment-client's upstream timeout is a configured
value, and the provider's p95 latency is not. When a release lowers the former below the
latter, normal provider behaviour starts failing checkout.

## First checks, in order

1. **Compare the configured upstream timeout against payment-api p95 for the same window.**
   If the timeout is below p95, stop here — you have the cause. Everything below is confirmation.
2. **Confirm which version is actually deployed.** A stored remediation that references a
   version no longer running is worse than no remediation: it is confidently wrong. The agent
   rejects stale matches for this reason, and a human should apply the same test.
3. **Check whether the provider is genuinely degraded** before blaming the provider. Provider
   status pages lag real degradation by several minutes, so prefer your own p95.

## Remediation

Roll back the payment-service release to restore the previous upstream timeout. Rolling back is
faster and lower-risk than tuning the provider, and it returns the system to a known state.

Do not "fix forward" by raising the timeout in a hotfix unless the rollback is unavailable:
the hotfix path has no soak time and the timeout value is exactly what is in dispute.

After service is restored:

- Add an alert on payment-api timeout rate, keyed to deploys rather than to a static threshold.
- Treat any change to an upstream timeout as requiring a current p95 measurement in the same
  change request. A timeout is a latency budget, and a budget set without measuring is a guess.

## What this incident class costs when handled badly

The expensive failure mode is not the outage. It is reading every log line across all three
services to rediscover a cause that is one configuration diff. Scoping telemetry to
checkout, payment-client and payment-api is sufficient for every occurrence recorded so far.
