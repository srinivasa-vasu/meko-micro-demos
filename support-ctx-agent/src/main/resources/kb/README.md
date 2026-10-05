# What belongs in the knowledge base, and what must never go there

> **Upload all but this file `README.md`.**
>
> It is repo guidance about seedKeys and chunkers, not operational content. Uploaded, it
> becomes a document the agent retrieves and feeds to a model during an incident — which is
> both useless and a small ongoing cost. If it is already in your datapack, delete it there.

Meko's "Shared Knowledge" is two different things, and `datapack_describe` counts them
separately:

| Field | What it is | How it gets there |
|---|---|---|
| `collective_memory_count` | Promoted memories. One record each, not chunked. | `memory_add` → `memory_promote` |
| `knowledge_base_file_count` | Uploaded documents, split into RAG chunks. | Upload through the Meko app |

`knowledgebase_search` searches across both. `knowledge_chunk_count` is the debugging view.

## The rule

Split by **who reads it**, not by how important it is.

**The model reads it → upload as Markdown.** Runbooks, postmortems, architecture notes,
on-call procedures. Prose benefits from chunking, and semantic search over chunks is exactly
what RAG is for. The files in this directory are these.

**The parser reads it → keep it as a single-line record.** `INCIDENT_RECORD`,
`ROUTING_OUTCOME`, `SPEND_POLICY`, `ROUTING_POLICY`. These are written by
`SeedService.seed()` and by the agent's write-back.

## Why the split is not negotiable

`MemoryRecordParser` anchors on `(?m)^INCIDENT_RECORD` and pulls fields with
`parseDouble(text, "budgetUsd_sev2", fallback)`. Both assume the whole record is intact on one
line.

A chunker does not know that. Put this in a Markdown file:

```
SPEND_POLICY; budgetUsd_sev1=0.50; budgetUsd_sev2=0.15; budgetUsd_sev3=0.05
```

...and a chunk boundary can land in the middle of it. `parseDouble` then finds nothing,
returns its fallback, and the run reports a spend ceiling that came from `application.yml`
while the UI says it came from Meko. No error, no warning — the demo just quietly stops being
true. That exact bug already shipped once here, for a different reason (the records were
seeded to mem0 and never promoted, so the knowledge-base lookup always missed).

So: **never put a `KEY; field=value` record inside an uploaded document.** Structured records
go through `memory_add` and are promoted. Prose goes in files.

## What to upload

| Document | Why it earns its place |
|---|---|
| `runbook-checkout-payment-timeouts.md` | The procedure for scenarios 1 and 2. |
| `runbook-search-index-staleness.md` | The procedure for scenario 3 — the run where memory correctly has nothing, so curated knowledge is the only context the model gets. |
| `postmortem-2026-03-02-payment-timeout.md` | The human narrative behind INC-1842. The structured INCIDENT_RECORD is what the gate scores; this is what a model reads. Same incident, two stores, two jobs. |
| `service-map-and-ownership.md` | Dependency topology and who owns what. Tells the model that a payment-service release changes payment-client behaviour without a payment-client deploy — the thing that cost 35 minutes in the postmortem. |
| `oncall-escalation-and-spend.md` | The reasoning behind the escalation and spend rules. The thresholds themselves stay structured; this is the why. |

Scenario 3 is the one to watch. Memory declines, correctly, and the runbook still gives the
model the right first check — which is the clearest argument in the demo for keeping curated
knowledge separate from learned memory.

## Uploading

Upload every `.md` file in this directory except this `README.md` to the datapack named in
`meko.datapack-name` through the Meko app. There is no `knowledgebase_add` on the MCP surface,
so this is a deliberate human step — which is appropriate: a runbook is curated content, not
something an agent should be able to invent and publish on its own.

After uploading, confirm with `datapack_describe(include_status=true)` that
`knowledge_base_file_count` is no longer 0.
