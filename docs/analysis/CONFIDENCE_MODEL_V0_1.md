# SignalFrame Confidence Model v0.1

Status: Frozen for v0.1 (normative)
Related: [ANALYSIS_PROTOCOL_V0_1.md](ANALYSIS_PROTOCOL_V0_1.md) · [EPISTEMIC_TYPES.md](EPISTEMIC_TYPES.md) · [SCHEMA_IMPACT_ASSESSMENT.md](SCHEMA_IMPACT_ASSESSMENT.md)

## 1. The one thing to remember

> **Confidence Score is not a probability of truth.**

A SignalFrame confidence score is an ordinal, auditable judgment about **how well the snapshot is
supported by the evidence we currently hold**. It is not the chance that a claim is true, not a
forecast, and not comparable across rubric versions. The contract already encodes the strongest form
of this statement: `ConfidenceAssessment.isProbability` must be `false`, and the validator rejects a
snapshot where it is `true`. That guard remains in force.

Corollaries that implementations must not violate:

- No calibrated language anywhere: never "80% likely", never "probability", never "odds".
- No arithmetic on scores that implies probability: no expected values, no score-weighted forecasts.
- No averaging of item scores into a snapshot score.
- A score is not comparable to a score from another rubric version or another protocol version.
- A higher score never means "verified". Verification is `status` and `verificationStatus`, not
  confidence.
- The score of a `CONFIRMED` prediction and the score of the parent hypothesis are separate facts
  about separate objects.

## 2. Why a deterministic rubric

Free-form model confidence is the single easiest place for a language model to fabricate precision.
A number like "74" produced by a model is not reproducible, not auditable, and not attributable to any
property of the evidence.

The rubric below fixes:

- **what is measured** — five named dimensions;
- **how each level is derived** — from fields that already exist in the snapshot;
- **the arithmetic** — deterministic, integer, no rounding;
- **the ceilings** — caps that make certain claims impossible regardless of eloquence.

A model may still express a number for diagnostics, but that number is advisory and must never be
stored as the rubric score (EP-09, CF-01).

## 3. Dimensions and weights

| id | dimension | weight | measures |
| --- | --- | --- | --- |
| D1 | Source Quality | 20 | what kind of input we have, and how complete it is |
| D2 | Evidence Directness | 25 | how directly the facts support the claim, and how many assumptions bridge the gap |
| D3 | Independent Corroboration | 20 | how many *independent* sources/methods confirm the same substance |
| D4 | Mechanism Support | 20 | whether a documented mechanism of the required support level exists |
| D5 | Counter-evidence Resilience | 15 | whether objections and contradicting evidence are sought out and handled |
| | **total** | **100** | |

Each dimension is scored at an integer level `0…5`. Dimension points are
`weight × level / 5`, which is exact for these weights:

| dimension | level 0 | 1 | 2 | 3 | 4 | 5 |
| --- | --- | --- | --- | --- | --- | --- |
| D1 (20) | 0 | 4 | 8 | 12 | 16 | 20 |
| D2 (25) | 0 | 5 | 10 | 15 | 20 | 25 |
| D3 (20) | 0 | 4 | 8 | 12 | 16 | 20 |
| D4 (20) | 0 | 4 | 8 | 12 | 16 | 20 |
| D5 (15) | 0 | 3 | 6 | 9 | 12 | 15 |

Because every weight is divisible by 5, **no rounding is ever required**:

```
raw   = D1_points + D2_points + D3_points + D4_points + D5_points
score = min(raw, min(applicable caps))        // §6
band  = bandOf(score)                          // §4, applied last
```

Every arithmetic step is integer addition and `min`. There is no floating point, no model call, no
clock, and no randomness in the computation.

## 4. Bands

| band | range |
| --- | --- |
| `VERY_LOW` | 0–29 |
| `LOW` | 30–49 |
| `MEDIUM` | 50–69 |
| `HIGH` | 70–84 |
| `VERY_HIGH` | 85–100 |

The ranges are contiguous and cover 0–100 exactly once. The band is derived from the **final** score
after caps, never from `raw`.

Band semantics, for UI copy and review:

- `VERY_LOW` — the snapshot is mostly a restatement of one input; treat every explanation as
  provisional. Typical shape: single source, no independent corroboration, no documented mechanism.
- `LOW` — some analysis is possible, but a core requirement is missing (corroboration, directness, or
  mechanism).
- `MEDIUM` — a defensible reading of the input, still missing at least one of corroboration,
  documented mechanism, or counter-evidence handling.
- `HIGH` — direct facts, a documented mechanism, and independent corroboration of the core fact. Still
  not a probability and still falsifiable by a prediction failing.
- `VERY_HIGH` — primary-grade source, direct facts, at least two independent sources confirming the core
  fact, a documented mechanism, and reconciled contradicting evidence. This band should be rare, and it
  is unreachable below `D3 ≥ 4` (CAP-F).

## 5. Level definitions and derivation

The tables below are the normative level functions. Each level must be derivable from fields present
in the snapshot; this is what makes Gate D (recomputation) possible. **A dimension's level is the
highest level whose condition is satisfied**, so the tables need not be mutually exclusive partitions
and a higher level does not require every lower condition to hold. That convention makes every level
function total over the inputs STG-02 can produce, so CF-06 (fail-closed to `MODEL_JUDGMENT`) only
ever triggers for snapshots missing the required fields entirely.

### D1 Source Quality (20)

Inputs: `sourceAssessment.sourceType`, `publisher`, `publishedAt`, `primaryOrSecondary`,
`contentCompleteness`, and the count of distinct independent sources in the snapshot.

| level | condition |
| --- | --- |
| 0 | `contentCompleteness ∈ {METADATA_ONLY, TRUNCATED, UNKNOWN}` **or** `publisher = UNKNOWN` |
| 1 | identifiable `publisher`; `contentCompleteness ∈ {COMPLETE, PARTIAL}` |
| 2 | identifiable `publisher`; `publishedAt` known; `contentCompleteness = COMPLETE` |
| 3 | level 2 plus `primaryOrSecondary = PRIMARY` (document, official statement, disclosure, filing) |
| 4 | level 3 plus at least one additional secondary source adding context |
| 5 | two or more independent primary-grade sources agreeing on the fact set |

D1 describes the quality of the best source the claim rests on; D3 separately counts *independent
confirmation of the core fact*. The two are therefore not redundant: a snapshot can legitimately be
D1 = 4 while D3 = 0 when an independent contextual piece exists but does not confirm the core claim.

### D2 Evidence Directness (25)

Inputs: `factRefs[]` presence and count, `assumptions[]`, `mechanisms[].supportLevel`, and whether the
core claim is a quantity/relationship stated in a fact.

| level | condition |
| --- | --- |
| 0 | no resolved `factRefs` |
| 1 | facts only restate the summary; the claim adds nothing the fact does not already say |
| 2 | facts are topical, but the link to the claim requires an unstated assumption |
| 3 | the claim needs exactly one assumption, and that assumption is documented in `assumptions[]` |
| 4 | the claim follows through a documented mechanism with `supportLevel ≥ PLAUSIBLE` |
| 5 | a fact states the core quantity or relationship directly (the number itself) |

### D3 Independent Corroboration (20)

Inputs: distinct `independenceKey` values among referenced sources; on-record statements, filings and
data series count as different methods; republished agency copy counts once (PR-12).

| level | condition |
| --- | --- |
| 0 | all evidence traces to a single source |
| 1 | additional material that is not independent (same publisher, syndicated copy, self-quotation) |
| 2 | one independent source partially overlapping the claim |
| 3 | one independent source confirming the core fact |
| 4 | two or more independent sources confirming the core fact |
| 5 | two or more independent sources using different methods, agreeing on the core fact |

**MVP reality check.** The current pipeline ingests one source per analysis, so D3 ∈ {0, 1} today.
`D3 ≤ 1` triggers CAP-A, which pins the ceiling at 49. This is intentional: the MVP cannot produce a
`HIGH` confidence claimed from a single article, and it should not pretend otherwise. D3 becomes
useful when multi-source ingestion lands; the rubric does not change.

### D4 Mechanism Support (20)

Inputs: `mechanisms[]` count and `supportLevel`.

| level | condition |
| --- | --- |
| 0 | no mechanism stated — the causal step is UNKNOWN |
| 1 | only `SPECULATIVE` mechanisms |
| 2 | a `PLAUSIBLE` mechanism with no fact refs on either end |
| 3 | a `PLAUSIBLE` mechanism with at least one fact ref on the cause side |
| 4 | a `SUPPORTED` mechanism with fact refs on both ends |
| 5 | as level 4 plus an independent corroborating signal or source |

### D5 Counter-evidence Resilience (15)

Inputs: `counterArguments[]`, `falsificationConditions[]`, `contradictingEvidenceRefs[]`,
`facts[].verificationStatus`, and verification plan items.

| level | condition |
| --- | --- |
| 0 | neither a counter-argument nor a falsification condition exists |
| 1 | exactly one of counter-argument / falsification condition exists |
| 2 | both exist, but neither references a fact or evidence |
| 3 | both exist and the strongest counter-argument carries a fact/evidence ref |
| 4 | level 3, no referenced fact has `verificationStatus = DISPUTED`, and falsification conditions are observable |
| 5 | contradicting evidence exists and is explicitly reconciled with refs, and falsification conditions are observable and time-bounded |

## 6. Caps (ceiling rules)

Caps make certain statements structurally impossible. They are applied after summing and take the
minimum; they are monotone and order-independent.

| id | condition | ceiling | rationale |
| --- | --- | --- | --- |
| CAP-A | `D3 ≤ 1` (no independent corroboration) | 49 | material that is not independent cannot lift a claim above `LOW` |
| CAP-B | a fact referenced by the main hypothesis has `verificationStatus = DISPUTED` | 69 | a disputed core fact forbids a `HIGH` band |
| CAP-C | no verification plan item with a distinguishable `supportingResult`/`contradictingResult` and a deadline | 59 | unfalsifiable-in-practice snapshots stay at most `MEDIUM` |
| CAP-D | `D4 ≤ 1` (no documented mechanism) | 59 | an unexplained relation cannot be well supported |
| CAP-E | `D2 ≤ 1` (facts do not carry the claim) | 49 | a claim that adds nothing to its facts is LOW at most |
| CAP-F | `D3 ≤ 3` (fewer than two independent confirming sources) | 84 | `VERY_HIGH` requires at least two independent sources confirming the core fact (`D3 ≥ 4`) |

Properties that follow, and must be stated in code comments and tests:

1. `VERY_HIGH` requires `D3 ≥ 4`, i.e. at least two independent sources confirming the core fact.
   CAP-F makes the top band unreachable below that.
2. `HIGH` requires `D3 ≥ 2` and none of CAP-A, CAP-B, CAP-C, CAP-D or CAP-E binding: documented
   corroboration, no disputed core fact, a usable verification plan, and facts that carry the claim.
3. A snapshot with no mechanism can reach at most 59; a snapshot with only a speculative mechanism is
   capped at 59 as well (CAP-D binds at D4 ≤ 1, and D4 = 1 gives 4 raw points).
4. Caps never raise a score.

## 7. Worked examples

All six examples use the tables above. Every number below is reproducible by hand.

**Example 1 — single report, no corroboration, no mechanism (the current mock shape).**
`D1=1` (publisher identifiable, no date) → 4 · `D2=2` (topical, unstated bridge) → 10 ·
`D3=0` → 0 · `D4=0` → 0 · `D5=2` (both present, no refs) → 6.
`raw = 20`. CAP-A applies (`D3=0`), ceiling 49; `20 < 49`, so no change. **score 20 → `VERY_LOW`.**

**Example 2 — a defensible reading, still missing corroboration depth.**
`D1=2` → 8 · `D2=4` → 20 · `D3=3` → 12 · `D4=3` → 12 · `D5=3` → 9.
`raw = 61`. CAP-A does not bind (`D3=3`), CAP-C does not bind (plan exists), CAP-D does not bind
(`D4=3`); CAP-F binds at `D3 = 3` with ceiling 84, which does not lower 61. **score 61 → `MEDIUM`.**

**Example 3 — primary-grade source with corroboration and a documented mechanism.**
`D1=3` → 12 · `D2=5` → 25 · `D3=4` → 16 · `D4=4` → 16 · `D5=4` → 12.
`raw = 81`. No cap binds. **score 81 → `HIGH`.**

**Example 4 — all five dimensions at top level.**
`D1=4` → 16 · `D2=5` → 25 · `D3=4` → 16 · `D4=5` → 20 · `D5=5` → 15.
`raw = 92`. No cap binds. **score 92 → `VERY_HIGH`.**

**Example 5 — the ceiling rule doing its job.**
`D1=4` → 16 · `D2=5` → 25 · `D3=0` → 0 · `D4=4` → 16 · `D5=2` → 6.
`raw = 63`, which would be `MEDIUM` on its own. CAP-A applies: `min(63, 49) = 49`.
**score 49 → `LOW`.** Without independent corroboration of the core fact, however good the primary
source, the score cannot reach `MEDIUM`.

**Example 6 — stacked caps.**
`D1=1` → 4 · `D2=1` → 5 · `D3=0` → 0 · `D4=1` → 4 · `D5=1` → 3.
`raw = 16`. CAP-A (49), CAP-D (59), CAP-E (49) and CAP-F (84) apply; the minimum ceiling is 49.
**score 16 → `VERY_LOW`.**

## 8. Scope: which object does the score describe?

`confidence` appears in several contract records. The scope is fixed as follows; no implementation may
average or propagate scores across scopes.

| scoped object | rubric profile | weights | notes |
| --- | --- | --- | --- |
| snapshot `confidenceAssessment` | D1, D2, D3, D4, D5 | 20/25/20/20/15 | normative primary profile; describes summary + main hypothesis |
| `Hypothesis.confidence` | D1, D2, D3, D4, D5 | 20/25/20/20/15 | scored at hypothesis scope against that hypothesis's own refs |
| `Statement` (INFERENCE items) | D1, D2, D5 | 30/40/30 | item profile |
| `Variable`, `CausalLink`, `StakeholderImpact`, `Indicator` | D1, D2, D4 | 30/40/30 | item profile |
| `Fact.confidence` | D1 only | 100 | **source-report reliability**, never truth; prefer `verificationStatus` |
| `Prediction` | — | — | predictions carry no confidence; they carry a `status` |

Item profiles reuse the same level definitions, read at item scope: D1 from the source(s) the item
references, D2 from that item's `factRefs` and assumptions, D4 from the mechanism the item participates
in, D5 from whether the item has an acknowledged counter-consideration. Item profile weights are also
divisible by 5, so item points are exact integers (30 → 6 per level, 40 → 8 per level).

Item scores are diagnostics for review. They must not be summed, averaged, or used to derive the
snapshot score.

## 9. Determinism contract

Frozen shape (see the interface freeze register in
[WAVE2_IMPLEMENTATION_PLAN.md](WAVE2_IMPLEMENTATION_PLAN.md) §6). The rubric lives in a neutral,
Spring-free package so both `analysis` and `research` can use it:

```java
public interface ConfidenceRubric {
  ConfidenceScore score(ConfidenceInputs inputs);   // pure, deterministic
}

/** Derived projection of the assembled snapshot. Every component feeds a level rule of §5. */
public record ConfidenceInputs(
    SourceAssessment source,                    // D1
    int independentSourceCount,                 // D3 levels 2-3, CAP-A
    int independentConfirmingSourceCount,       // D3 levels 4-5, CAP-F
    int independentMethodCount,                 // D3 level 5
    int nonIndependentAdditionCount,            // D3 level 1
    List<Fact> facts,                           // D2, CAP-B, CAP-E
    List<Variable> variables,                   // D2 context
    List<CausalLink> mechanisms,                // D4, CAP-D
    List<Statement> counterArguments,           // D5
    List<Statement> falsificationConditions,    // D5
    List<CorroboratingSignal> signals,          // D4 level 5, D5
    List<Indicator> plan) {}                    // CAP-C

/** ConfidenceBand and ConfidenceDimension are the generated contract records from SCH-06/SCH-09. */
/** capped = true when one or more CAP-x ceilings lowered the raw total. */
public record ConfidenceScore(int score, ConfidenceBand band, String rubricVersion,
                              List<ConfidenceDimension> dimensions, String reason,
                              boolean capped) {}
```

The projection is derived from the snapshot, never from a model. The D3 inputs resolve the level table
directly: `independentSourceCount == 0` means D3 ∈ {0, 1} (distinguished by
`nonIndependentAdditionCount`), one independent source gives D3 ∈ {2, 3} depending on whether it
confirms the core fact, `independentConfirmingSourceCount` gives D3 ∈ {4, 5}, and
`independentMethodCount ≥ 2` selects level 5. `facts`/`mechanisms`/`plan` come from the assembled
artifacts, and the boolean facts an item
participates in (documented assumption, refs on both mechanism ends, observable condition) are
computed by the same deterministic pass that builds the levels.

`CF-01` A model must never supply the stored score. A model-supplied number, if present, is stored only
in the diagnostic field `advisoryScore` (SCH-09) and is ignored by every reader.
`CF-02` The score is a pure function of `(dimension levels, the §6 cap conditions, rubric version)`;
the levels are pure
functions of the snapshot fields listed in §5.
`CF-03` Recomputing the rubric for a stored snapshot must reproduce `score`, `band`, and every
dimension's `points` exactly. A mismatch fails validation (Gate D).
`CF-04` The rubric has no input that is not in the snapshot (no wall clock, no network, no model, no
random, no environment).
`CF-05` Caps are applied in a single `min` over all binding caps; implementation order must not
change the result.
`CF-06` If a dimension cannot be derived from a legacy snapshot, the scorer must fail closed: mark the
assessment `method = MODEL_JUDGMENT`, expose no rubric dimensions, and keep the score advisory. It
must not guess a level.
`CF-07` Scoring is versioned. Any change to weights, levels or caps increments `rubricVersion`;
historical snapshots keep the version they were scored with.
`CF-08` Gate D enforcement: `method = RUBRIC` requires `rubricVersion`, `dimensions[]` with all
dimensions of the profile, `score == min(sum(points), applicable caps)` and
`band == bandOf(score)`.

## 10. Compatibility migration (current code → deterministic scoring)

The current code lets the model emit `confidenceAssessment.score` directly (prompt text: "Confidence
0–100 is judgment, not a statistical probability"; `MockModelGateway` emits 30 for its demo, facts at
40, hypothesis at 30, unknown items at 20). This round changes **no implementation**. The migration is
staged so that each wave stays small and nothing breaks existing stored payloads.

**Phase 0 — as of this commit (no code change).**
Model numbers are the only confidence available. They are read as `method = MODEL_JUDGMENT`. UI copy
must keep saying the score is a judgment scale, not a probability. No schema field is required; readers
infer `MODEL_JUDGMENT` from the absence of `method` (SCH-09 is additive and nullable).

**Phase 1 — Wave 2A, TASK-03.**
1. Implement the deterministic rubric as a pure component invoked after assembly in
   `SynthesizeAnalysis` (no new model purpose, no new provider work).
2. Derive the five levels from the assembled snapshot per §5; apply caps; store `score`, `band`,
   `rubricVersion = "0.1"`, `dimensions[]`, `method = RUBRIC`, and a `reason` rendered from the
   dimension notes.
3. Move any model-supplied number to `advisoryScore` for diagnostics; never let it influence the
   stored score.
4. Add Gate D to validation for new snapshots. Legacy payloads without `method` are not re-validated
   against Gate D — they are read as advisory.
5. Tests: the six worked examples as exact-value cases, plus a property test asserting
   `recompute(snapshot) == stored` over generated level vectors.

**Phase 2 — Wave 2B, TASK-06 and TASK-07.**
1. Hypothesis-level confidence uses the rubric at hypothesis scope; `HypothesisEvent` records
   `rubricVersion` and the dimension breakdown in its payload alongside `previousConfidence` and
   `confidence`.
2. Evidence changes confidence **only** by changing rubric inputs (D2/D3/D4/D5), never by adding an
   arbitrary delta. A confidence event must name the dimension(s) that moved.
3. Status transitions map to band movement deterministically:
   new supporting independent evidence raises D3/D2 → `STRENGTHENING`; contradicting evidence raises
   the D5 burden and lowers D3/D2 contributions while the contradiction stands → `WEAKENING`; a
   falsification condition met → `REJECTED`; resolved prediction with no decisive outcome → `UNRESOLVED`.
   `CONFIRMED` requires a verification record, not a score.
4. Optimistic concurrency and append-only events (TASK-06) are unchanged by this model.

**Reader compatibility rules.**
- New fields are nullable; absent means `MODEL_JUDGMENT`.
- Historical `analyses.payload` rows are never rewritten or backfilled.
- The web view must render `band` and `method` when present, and must keep the "judgment scale, not a
  probability" notice in all cases.
- Sorting or filtering by confidence across protocol versions is not allowed; a UI that does so must
  display the version.

## 11. Limitations (must be stated, not hidden)

- **Not calibrated.** No base rate, no historical accuracy tracking, no reliability curve. The bands
  are ordinal.
- **Inputs can still be model-generated.** The rubric removes the model's *number*, not its *reading*:
  facts, refs, mechanism support levels and counter-arguments come from model-backed stages in Wave 2A.
  Garbage in still yields a confidently-scored, internally consistent snapshot. Gate B/C mitigate this;
  they do not eliminate it.
- **Single-source MVP.** An analysis ingests one source, so D3 ∈ {0, 1} in practice, CAP-A binds, and
  both `MEDIUM` and `HIGH` are unreachable until multi-source ingestion exists. That is a true statement
  about the MVP, not a bug.
- **Level boundaries are conventions.** The step from D2 = 2 to D2 = 3 is a judgment about whether an
  assumption is documented. Documented, testable, and auditable — but still a convention.
- **Caps encode policy.** CAP-A and CAP-F reflect a deliberate product stance ("no independent
  corroboration above `LOW`", "the top band requires two independent confirming sources"). They must be
  visible in review, not treated as mathematics.
- **No cross-domain comparison.** A `MEDIUM` in FINANCE and a `MEDIUM` in POLICY are not the same
  amount of support; the rubric is domain-invariant by design, and domain context enters through
  strategies' recommended evidence, not through weights.
