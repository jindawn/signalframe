# SignalFrame Analysis Protocol (docs/analysis)

This directory is the contract-of-record for how SignalFrame turns news into a verifiable world model.
Code is expected to conform to these documents; when code and document disagree, the document wins until
a version bump says otherwise.

Read in this order:

1. [EPISTEMIC_TYPES.md](EPISTEMIC_TYPES.md) — what FACT, INFERENCE, HYPOTHESIS, PREDICTION and UNKNOWN
   mean, and the hard rules (EP-01…EP-12) that keep them apart.
2. [ANALYSIS_PROTOCOL_V0_1.md](ANALYSIS_PROTOCOL_V0_1.md) — the 16-stage chain, the mapping onto the 14
   fixed pipeline steps, provenance/determinism rules (PR-01…PR-18) and the A–E validation gates.
3. [CONFIDENCE_MODEL_V0_1.md](CONFIDENCE_MODEL_V0_1.md) — the deterministic rubric that replaces
   model-chosen confidence, its bands, caps, worked examples and the compatibility migration path.
4. [DOMAIN_STRATEGY_CONTRACT.md](DOMAIN_STRATEGY_CONTRACT.md) — the domain-strategy boundary, the
   interface proposal and the twelve domain dictionaries.
5. [SCHEMA_IMPACT_ASSESSMENT.md](SCHEMA_IMPACT_ASSESSMENT.md) — what the current contract already
   supports, what must be extended, and the persistence/tooling constraints.
6. [proposals/SCHEMA_PROPOSAL_V0_2.md](proposals/SCHEMA_PROPOSAL_V0_2.md) — SCH-01…SCH-14 as ready-to-apply
   patches, with blast radius and sequencing. **Not applied yet.**
7. [WAVE2_IMPLEMENTATION_PLAN.md](WAVE2_IMPLEMENTATION_PLAN.md) — Wave 2A (TASK-03, TASK-05) and Wave 2B
   (TASK-06, TASK-07): owned paths, interfaces, dependencies, migrations and gates.

Conventions used across these documents:

| prefix | meaning |
| --- | --- |
| `STG-nn` | protocol stage (ANALYSIS_PROTOCOL_V0_1.md) |
| `EP-nn` | epistemic rule (EPISTEMIC_TYPES.md) |
| `PR-nn` | protocol invariant (ANALYSIS_PROTOCOL_V0_1.md) |
| `CF-nn`, `CAP-x`, `Dn` | confidence rules, caps and dimensions (CONFIDENCE_MODEL_V0_1.md) |
| `DS-nn` | domain strategy rule (DOMAIN_STRATEGY_CONTRACT.md) |
| `SCH-nn` | schema proposal (proposals/SCHEMA_PROPOSAL_V0_2.md) |

Status of this round: documentation only. No prompt, pipeline, strategy, hypothesis engine, evidence
engine, retrieval or UI implementation is included, and `contracts/openapi.yaml` is unchanged.
