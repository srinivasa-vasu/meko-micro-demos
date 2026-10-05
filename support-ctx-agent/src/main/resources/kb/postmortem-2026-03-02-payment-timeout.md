# Postmortem: checkout failures from a payment timeout change (2026-03-02)

**Incident:** INC-1842 · **Severity:** SEV2 · **Duration:** 47 minutes
**Services affected:** checkout, payment-client, payment-api

## What happened

At 08:51Z a payment-service release lowered the upstream payment provider timeout from 5s to
3s. The change was made to shed load during a period of elevated queue depth, and it was
reasonable on its own terms — the queue depth was real.

The provider's p95 latency in the preceding week was 4.8s. Lowering the client timeout below
the provider's normal p95 meant that ordinary provider behaviour started producing client-side
timeouts. Checkout error rate went from 0.7% to 18.2% within nine minutes of the deploy.

We rolled back at 09:38Z. Error rate returned to baseline within two minutes.

## Why it took 47 minutes

Twelve of those minutes were detection. The remaining thirty-five were spent looking in the
wrong place. The checkout error messages named the payment provider, so the first hypothesis was
provider degradation, and the provider's own status page showed green — which we did not trust,
because status pages lag. We spent twenty minutes correlating provider metrics before anyone
compared the *configured timeout* against our own measured p95.

The deploy was visible the whole time. It was in the change log, it was nine minutes before the
error rate moved, and nobody connected it because the error text pointed outward.

## What we changed

- Alert on payment-api timeout rate, keyed to deploys rather than a static threshold.
- Any change to an upstream timeout now requires a current p95 measurement in the same change
  request. A timeout is a latency budget, and a budget set without measuring is a guess.
- Checkout error messages now include whether the timeout was client-side or provider-reported.

## What we would tell the next person

When an error names an upstream dependency, check what *you* changed about how you talk to it
before you investigate the dependency. The error text describes where the failure surfaced, not
where it originated. In this incident the error said "payment provider timeout" and the cause
was a number in our own configuration.

Correlate by *time to deploy*, not by error text. Errors that track a release and not traffic
volume are configuration, not capacity.
