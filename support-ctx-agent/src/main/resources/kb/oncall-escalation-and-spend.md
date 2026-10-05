# On-call: model escalation and investigation spend

This note explains the *reasoning* behind the routing and spend rules. The machine-readable
thresholds live in structured `SPEND_POLICY` and `ROUTING_POLICY` records, not in this file —
see `README.md` in this directory for why that separation matters.

## Escalate on evidence, not on nerves

Escalating to a larger model is justified by one of two things:

- **Low confidence.** The model says it does not know. Take it at its word.
- **Failed validation.** The remediation asserts something about the system that is not true —
  most often a version claim that contradicts what is deployed. This is the dangerous case,
  because a confident answer and a wrong answer look identical until the assumption is checked.

Escalating because an answer *feels* thin is not a reason. It is how a SEV3 costs SEV1 money.

## Spend is a severity decision

A SEV3 that consumes a SEV1 budget has taken that budget from a real SEV1 later in the week.
The ceiling is not a cost-control gesture; it is a prioritisation mechanism. If an
investigation cannot be completed within the ceiling, that is a signal to involve a human, not
a signal to raise the ceiling.

## Reuse is a suggestion, never an action

A stored remediation that matches strongly is a starting point for a human, not an instruction
to a system. Two conditions must hold before it is even worth reading:

1. The deployment state it references is still current.
2. Its stated assumptions still hold against live state.

Both are cheap to check and both are checked automatically. Neither is a substitute for the
on-call engineer deciding to apply it.

## When history is wrong

The corpus is written by previous runs, which means it can be confidently wrong in exactly the
way the last engineer was. If a stored root cause does not match what you are seeing, say so in
the incident record. A corpus that only ever confirms itself is worse than no corpus, because
it is persuasive.
