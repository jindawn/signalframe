# SignalFrame Epistemic Types

Status: Frozen for v0.1 (normative)
Applies to: TASK-03, TASK-05, TASK-06, TASK-07
Related: [ANALYSIS_PROTOCOL_V0_1.md](ANALYSIS_PROTOCOL_V0_1.md) · [CONFIDENCE_MODEL_V0_1.md](CONFIDENCE_MODEL_V0_1.md) · [SCHEMA_IMPACT_ASSESSMENT.md](SCHEMA_IMPACT_ASSESSMENT.md)

This document fixes the meaning of the five epistemic types. Every module, prompt, schema field,
validator and domain strategy must obey it. The meanings are **not** domain-specific and **not**
implementation-specific: a domain strategy may recommend variables and mechanisms, but it may not
change what FACT, INFERENCE, HYPOTHESIS, PREDICTION or UNKNOWN mean.

## 1. Why these types exist

SignalFrame is not a news summarizer. A summary collapses "someone said X", "X follows from the
documents", "X is the best current explanation of the documents", and "X will be observable later"
into one undifferentiated sentence. That collapse is exactly the failure this protocol prevents.
The five types are the only permitted epistemic states of an output item.

## 2. Canonical definitions

### 2.1 FACT

A statement **directly supported by a current source**, reproduced with provenance.

- FACT does **not** mean absolute objective truth.
- FACT means: "a source in this analysis snapshot says this, and here is the exact span."
- Every FACT MUST have at least one `sourceRefs` entry.
- A FACT's `verificationStatus` MUST be one of `REPORTED`, `CORROBORATED`, `DISPUTED`.
  - `REPORTED`: a source asserts it; nothing independent confirms it yet. This is the default.
  - `CORROBORATED`: at least one **independent** source (different publisher ownership, different
    method) asserts the same substance.
  - `DISPUTED`: a source or an independent record conflicts with it.
- `CORROBORATED` is a statement about **sources**, never a claim that the fact is objectively true.
- `FACT.confidence`, where it exists in the current contract, is **source-report reliability**
  (how well the reporting process appears to hold up), never "probability the world is like this".
- FACT is the only type allowed in `AnalysisResult.facts`. FACT is forbidden everywhere else.

### 2.2 INFERENCE

A conclusion **derived from one or more FACTs** by explicit reasoning.

- Every INFERENCE MUST reference the FACT ids it rests on (`factRefs`). An INFERENCE with no fact
  reference is invalid output, not a low-confidence claim.
- Every INFERENCE MUST state the reasoning step that connects the fact to the conclusion.
- An INFERENCE may be wrong. Later evidence may demote it or delete it from the next snapshot; it is
  never "upgraded" to FACT by repetition, eloquence, or model consensus.
- Repeated assertion is not evidence: two INFERENCEs that share one FACT do not corroborate each
  other.

### 2.3 HYPOTHESIS

A **tentative explanation** of the facts and phenomena, held provisionally.

A HYPOTHESIS MUST be:

1. falsifiable — there exists an observable outcome that would count against it;
2. explicit about its assumptions;
3. accompanied by supporting evidence references;
4. accompanied by contradicting evidence references (an empty list plus a written search note is
   acceptable; silence is not);
5. accompanied by at least one alternative explanation of the same facts.

Required design fields (see [SCHEMA_IMPACT_ASSESSMENT.md](SCHEMA_IMPACT_ASSESSMENT.md) SCH-06):
`id, title, statement, sourceRefs, supportingFactRefs, supportingEvidenceRefs,
contradictingEvidenceRefs, assumptions, alternativeHypothesisRefs, falsificationConditions,
confidenceScore, confidenceBand, confidenceReason, status`. Protocol `confidenceScore` maps to the
contract's `Hypothesis.confidence`; `confidenceBand` is the derived band (SCH-06, SCH-09).

`status` vocabulary for the protocol:

| status | meaning |
| --- | --- |
| `OPEN` | newly stated, no decisive evidence movement yet |
| `STRENGTHENING` | new supporting evidence; competing explanations weakened |
| `WEAKENING` | new contradicting evidence; assumptions stressed |
| `CONFIRMED` | the pre-registered prediction(s) came true through the verification plan |
| `REJECTED` | a falsification condition was met |
| `UNRESOLVED` | evidence closed out without decisive result, or the deadline passed with no data |

`CONFIRMED` requires a verified prediction snapshot. It never means "true".

### 2.4 PREDICTION

A **judgment about a future observable outcome** derived from a hypothesis.

A PREDICTION MUST be:

- observable: a third party could check it without asking the analyst;
- time bounded: it has `expectedBy`;
- verifiable: it names the verification criteria and the place to look;
- linked to the hypothesis it tests (`hypothesisId` or `hypothesisRef`).

`status`: `OPEN`, `CONFIRMED`, `REJECTED`, `PARTIAL`, `UNRESOLVED`.
A prediction is judged against reality, not against the analyst's confidence. A high-confidence
hypothesis whose prediction fails is weakened; a prediction is never rewritten after the fact.

### 2.5 UNKNOWN

Insufficient information to state any of the above.

- UNKNOWN is a **first-class output**, not a failure and not an empty string.
- UNKNOWN carries: what is missing, why it is missing, and what would resolve it.
- UNKNOWN MUST NOT be used as an INFERENCE with vague wording ("possibly", "it is believed that").
- UNKNOWN MUST NOT be cited as support by any INFERENCE, HYPOTHESIS or PREDICTION.

## 3. Hard rules (normative)

| id | rule |
| --- | --- |
| EP-01 | `Prefer UNKNOWN over unsupported inference.` If the required fact references do not exist, emit UNKNOWN. |
| EP-02 | `Missing information is not permission to infer.` Absence of a counter-argument, a previous state, or a published date is reported as UNKNOWN, not filled in. |
| EP-03 | `Do not invent causal links.` A CausalLink exists only if a mechanism is stated with a support level and fact references. No mechanism means `mechanisms: []` plus an UNKNOWN item. |
| EP-04 | `Do not turn source claims into independently verified facts.` Source-reported claims stay `REPORTED` until an independent source confirms the same substance, and even then FACT ≠ truth. |
| EP-05 | FACT requires `sourceRefs`; every non-FACT item that rests on facts requires `factRefs`. |
| EP-06 | UNKNOWN items must never appear in `facts`, and must never be referenced as supporting evidence. |
| EP-07 | Alternatives and counter-arguments are different artifacts and must not be merged. See §5. |
| EP-08 | Evidence and corroborating signals are different artifacts and must not be merged. See §5. |
| EP-09 | A model's stated confidence is advisory input only. It may never be recorded as the rubric score (see [CONFIDENCE_MODEL_V0_1.md](CONFIDENCE_MODEL_V0_1.md)). |
| EP-10 | Epistemic type is immutable across the item's lifetime within a snapshot: an INFERENCE cannot become a FACT in place. A new snapshot states a new item. |
| EP-11 | Confidence must not be attached where semantics forbid it: a PREDICTION's own confidence is the confidence of its parent hypothesis, and a FACT's number is reporting reliability. |
| EP-12 | No type may be redefined, renamed, added or removed by a domain strategy, prompt, or provider adapter. |

## 4. Required artifacts per type

| type | statement | provenance | assumption/detail | time | verification |
| --- | --- | --- | --- | --- | --- |
| FACT | `statement` (verbatim source span) | `sourceRefs[]` (required) | `verificationStatus` | — | disagreements recorded as `DISPUTED` |
| INFERENCE | `statement` + `reasoning` | `sourceRefs[]` + `factRefs[]` (required) | `assumptions[]` when a premise is unstated | — | falsification condition recommended |
| HYPOTHESIS | `title` + `statement` | `sourceRefs[]`, `supportingFactRefs[]`, `supportingEvidenceRefs[]` | `assumptions[]`, `contradictingEvidenceRefs[]`, `alternativeHypothesisRefs[]` | `createdAt`/`updatedAt` | `falsificationConditions[]`, `confidenceBand` |
| PREDICTION | `statement` + `observable` | `hypothesisRef`, `basisFactRefs[]` | `verificationCriteria` | `expectedBy` | `status`, `whereToCheck` |
| UNKNOWN | `statement` (what is unknown) | optional `sourceRefs[]` for the gap | `unknownReason` (why), `wouldResolveWith` | — | — |

## 5. Distinctions that must never be blurred

### 5.1 Alternative Explanation ≠ Counter Argument

- **Alternative Explanation** — a *different explanatory model* that accounts for the same facts.
  Example: "subscribers fell because of a price change" vs "subscribers fell because of a
  competitor's bundle". Both explain the same observation.
- **Counter Argument** — the *strongest objection to the current main hypothesis*, which may or may
  not propose a rival model. Example: "the reported subscriber figure is a self-selected
  disclosure, so the drop may be definitional."

They can overlap, but they answer different questions: *"what else could be going on?"* versus
*"why might I be wrong?"* A snapshot must be able to be read as: main hypothesis, the best rival
model, and the best objection.

### 5.2 Evidence ≠ Corroborating Signal

- **Evidence** is *already in existence*: a document, record, statement, or dataset that supports or
  contradicts the hypothesis. It has a source, a stance (`SUPPORTS` / `CONTRADICTS` / `NEUTRAL`) and
  a strength.
- **Corroborating Signal** is a *hypothetical future observation*: "if this hypothesis is right,
  we should be able to see X somewhere else within window W." It has an observable, a place, and a
  window — but no stance, because nothing has been observed yet.

An observed corroborating signal becomes Evidence in a later snapshot. It never becomes Evidence by
being described.

### 5.3 INFERENCE ≠ HYPOTHESIS

An INFERENCE follows from facts by a stated step. A HYPOTHESIS *explains* facts and is held
provisionally with rivals and falsification conditions. If it cannot be falsified, it is not a
hypothesis; it is commentary and must be dropped or emitted as INFERENCE/UNKNOWN.

### 5.4 PREDICTION ≠ Corroborating Signal ≠ upcoming observation

A PREDICTION is a dated, checkable claim with criteria and a status that will be resolved.
A Corroborating Signal is an expected observable that would raise confidence if found.
`upcomingObservations` (current contract) is only a reading list; it must not be treated as a
prediction and is scheduled for deprecation (SCH-07).

## 6. Representation in the current contract (v0.1) and the target (v0.2)

| type | current contract | gap | proposal |
| --- | --- | --- | --- |
| FACT | `Fact` with `type` + `sourceRefs` | no `verificationStatus` | SCH-02 |
| INFERENCE | `Statement.type = INFERENCE`, `CausalLink`, `Variable` | no `factRefs` | SCH-03 |
| HYPOTHESIS | `Hypothesis` record | no refs, assumptions, per-hypothesis falsification; `status` vocabulary differs; no `confidenceBand` | SCH-06 |
| PREDICTION | `Prediction` record exists but is **not emitted by `AnalysisResult`**; `status` lacks `OPEN` | not part of the analysis snapshot | SCH-07 |
| UNKNOWN | `unknowns: Statement[]`, forced into `ClaimType` | UNKNOWN is not a type; it is mislabeled | SCH-05 |
| Evidence | `Evidence` relational record, not part of the snapshot payload | no `factRefs`; no link back to the producing analysis | SCH-11 |
| Corroborating Signal | `corroboratingSignals: Statement[]` | no observable, window, or status | SCH-10 |

Until the proposals land, the current schema is read as follows, and **no implementation may read it
the other way**:

- `unknowns` items are UNKNOWN regardless of their declared `type`. `type` is a legacy artifact.
- `corroboratingSignals` items are *expected observables*, never evidence.
- `upcomingObservations` items are reading-list items, never predictions.
- `Hypothesis.status` legacy values map as: `OPEN→OPEN`, `SUPPORTED→STRENGTHENING`,
  `CHALLENGED→WEAKENING`, `REJECTED→REJECTED`, `ARCHIVED→UNRESOLVED`. The legacy value is preserved
  on read; only the protocol-level interpretation is mapped.

## 7. Validation hooks

These checks belong to `AnalysisResultValidator` (ai/application) and to the Wave 2 stage validators.
They are listed here as the normative acceptance surface; scheduling is in
[WAVE2_IMPLEMENTATION_PLAN.md](WAVE2_IMPLEMENTATION_PLAN.md).

1. FACT-only in `facts`; every FACT has a non-empty, in-range, verbatim `sourceRefs`.
2. No FACT type outside `facts`.
3. Every INFERENCE-ish item has at least one `factRefs` entry or an explicit UNKNOWN marker.
4. A HYPOTHESIS with an empty `assumptions` or empty falsification conditions is rejected in
   `STG-09`/`STG-12`, or explicitly downgraded to INFERENCE.
5. UNKNOWN items are never cited as supporting evidence.
6. `PREDICTION.status = CONFIRMED` requires a verification record; a model may not assert it.
7. Confidence stored as a rubric score must equal the deterministic recomputation (CF-08).

## 8. Anti-patterns (reject in review)

- "Sources say X, therefore X" — that is a FACT plus an elided INFERENCE, and the elision is the bug.
- "Given the above, it is likely that…" with no fact refs — UNKNOWN or a named INFERENCE with refs.
- A causal diagram produced with `supportLevel: SUPPORTED` and no fact references.
- Alternative explanations that are stylistic restatements of the main hypothesis.
- Counter arguments that are actually disagreements with the source, not with the hypothesis.
- Corroborating signals listed as if already observed.
- `DISPUTED` facts quietly dropped instead of surfaced.
- Any UNKNOWN rendered as an empty array with no explanation; "unknowns: []" on a single-source
  article is itself a red flag.
