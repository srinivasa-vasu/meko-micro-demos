# RB-PAY-12: Payment API latency after a deployment (SRE runbook)

Fictional demo runbook for the Meko GOAP demo. Owner: Platform SRE. Trigger: payment API p95 latency above 5 seconds
within an hour of a payments-service deployment.

## RB-PAY-12 steps, in order

1. Correlate the latency spike with the deploy timestamp.
2. Check database saturation (CPU, active connections, lock waits).
3. Verify connection-pool saturation on the payments service: pool in use versus maxPoolSize, and threads waiting
   for a connection.
4. Only then decide on a rollback or a configuration fix.

## RB-PAY-12 known cause

A release that lowers maxPoolSize exhausts the pool under normal load: latency rises while the database looks healthy.
Restoring the pool size mitigates faster than a full rollback.

## RB-PAY-12 handover

Checkpoint findings after each step so another responder, person or agent, can resume without repeating finished steps.
