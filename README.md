# Meko micro demos

Small, self-contained Spring Boot apps that show what [Meko](https://mekodata.ai/) adds to AI agents: memory,
knowledge and decision traces that persist across runs and are shared across agents and system boundaries. Two of the
demos plan with [Embabel](https://github.com/embabel/embabel-agent) GOAP; the third is a console for Meko's MCP tools.

Each demo is its own Maven project with its own README. Start there for the details.

| Demo | What it shows | Port |
|---|---|---|
| [`goap-ctx-agent`](goap-ctx-agent/README.md) | Five agent workflows across system boundaries, run side by side without Meko, with your own database, and with Meko. The same agents and the same GOAP planner; only the context changes. | 8080 |
| [`support-ctx-agent`](support-ctx-agent/README.md) | An AI SRE incident commander. Memory is consulted first and decides how much telemetry is read, which model tier is used, and whether a model is needed at all. Every run reports its cost against the no-memory baseline. | 8081 |
| [`meko-console`](meko-console/README.md) | A debugging console for Meko's MCP surface. Call any tool and see exactly what was sent and what came back. | 8090 |

Each demo has its own port, so all three can run at once. All of them read the Meko key from `MEKO_API_KEY`.

## Requirements

- Java 21+ and Maven
- Spring Boot 4.1.1; Embabel Agent 1.5.1 (`goap-ctx-agent`, `support-ctx-agent`)
- A Meko API key and datapack, for live Meko. `goap-ctx-agent` also runs offline with an in-memory stand-in.
- A model provider key for `support-ctx-agent` (OpenRouter by default, or Groq or Gemini)

## Quick start

```bash
# Runs offline; "With Meko" uses an in-memory stand-in
cd goap-ctx-agent
mvn spring-boot:run
open http://localhost:8080
```

```bash
cd support-ctx-agent
export MEKO_API_KEY=mko_tkn_...
export MEKO_DATAPACK_ID=...            # or MEKO_DATAPACK_NAME
export OPENROUTER_API_KEY=...
mvn spring-boot:run
open http://localhost:8081
```

```bash
cd meko-console
export MEKO_API_KEY=...
mvn spring-boot:run
open http://localhost:8090
```

Live Meko setup for each demo (knowledge base documents to upload, seeding the datapack, model profiles) is in that
demo's README.

## Repository layout

```
goap-ctx-agent/      Embabel GOAP stories, with and without Meko
support-ctx-agent/   Incident commander: memory-first planning and cost reporting
meko-console/        Meko MCP debugging console
```

In the two agent demos, `src/main/resources/kb/` holds the fictional runbooks, policies and standards the demos upload to a
Meko knowledge base.
