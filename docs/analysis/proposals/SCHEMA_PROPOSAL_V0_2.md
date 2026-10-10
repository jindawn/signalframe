# Schema Proposal v0.2 (Analysis Protocol)

Status: Proposal. **Not applied in this commit.** Integrator-owned.
Derived from: [SCHEMA_IMPACT_ASSESSMENT.md](../SCHEMA_IMPACT_ASSESSMENT.md) · [ANALYSIS_PROTOCOL_V0_1.md](../ANALYSIS_PROTOCOL_V0_1.md)
Applies to: `contracts/openapi.yaml` (+ regenerated artifacts) and the Wave 2 migrations.

## 0. Suppositions

1. The chain and existing field names are frozen. No renames, no removals.
2. **All new fields on existing schemas are optional** (not listed in `required`) and nullable where the
   protocol permits absence. Protocol-level mandates are enforced by the epistemic validator (Gate B),
   which can produce a precise message; Jakarta bean validation is used only for structural and range
   checks. Nullability exists so that legacy JSONB payloads and third-party readers keep working.
3. Generated artifacts are never hand-edited. Applying this proposal means: edit
   `contracts/openapi.yaml`, run `npm run contracts`, then update the handwritten call sites listed in
   §17. `npm run contracts:check` is the acceptance gate.
4. Only the schema subset supported by `scripts/generate-java.py` is used: objects, string enums,
   arrays, primitives, `nullable`, `$ref`. No `oneOf`/`anyOf`/`allOf`.
5. Applying the proposal in one wave is atomic for the module: every new record field changes the
   canonical constructor, so all call sites move together.

## 1. SCH-01 — `SourceAssessment` (Wave 2A)

New schema:

```json
"SourceAssessment": {
  "type": "object",
  "additionalProperties": false,
  "required": ["sourceType", "publisher", "publishedAt", "primaryOrSecondary", "independence", "contentCompleteness", "notes"],
  "properties": {
    "sourceType": {
      "type": "string",
      "enum": ["PRIMARY_DOCUMENT", "OFFICIAL_STATEMENT", "COMPANY_DISCLOSURE", "PRESS_RELEASE", "NEWS_REPORT", "WIRE_REPUBLICATION", "OPINION_ANALYSIS", "SOCIAL_POST", "UNKNOWN"]
    },
    "publisher": { "type": "string", "nullable": true },
    "publishedAt": { "type": "string", "format": "date-time", "nullable": true },
    "primaryOrSecondary": { "type": "string", "enum": ["PRIMARY", "SECONDARY", "UNKNOWN"] },
    "independence": { "type": "string", "enum": ["SINGLE_SOURCE", "SAME_PUBLISHER_DUPLICATE", "INDEPENDENT_SET", "UNKNOWN"] },
    "independenceKey": { "type": "string", "nullable": true },
    "contentCompleteness": { "type": "string", "enum": ["COMPLETE", "PARTIAL", "TRUNCATED", "METADATA_ONLY", "UNKNOWN"] },
    "notes": { "type": "array", "items": { "type": "string" } }
  }
}
```

`AnalysisResult.properties` gains:

```json
"sourceAssessment": { "$ref": "#/components/schemas/SourceAssessment", "nullable": true }
```

Notes: `publisher`/`publishedAt` live here and **not** on `Source`, because `Source` is ingestion-owned
(TASK-02) and must not grow analysis fields. `independenceKey` supports PR-12 dedupe of syndicated
copy. Acceptance: STG-02 derivation is deterministic; a single-source snapshot yields
`independence = SINGLE_SOURCE`, `publishedAt = null` when the source has no date.

## 2. SCH-02 — `Fact.verificationStatus` (Wave 2A)

```json
"verificationStatus": { "type": "string", "nullable": true, "enum": ["REPORTED", "CORROBORATED", "DISPUTED"] }
```

Absent means `REPORTED` (legacy payloads). `CORROBORATED` requires an independent source in the
snapshot and is unreachable in the single-source MVP; the validator must reject it there rather than
trust it.

## 3. SCH-03 — reference fields on `Statement` and `StakeholderImpact` (Wave 2A)

`Statement.properties` gains:

```json
"factRefs": { "type": "array", "items": { "type": "string", "format": "uuid" } },
"derivedFromRefs": { "type": "array", "items": { "type": "string", "format": "uuid" } },
"targetHypothesisRef": { "type": "string", "format": "uuid", "nullable": true },
"rivalsHypothesisRef": { "type": "string", "format": "uuid", "nullable": true }
```

`StakeholderImpact.properties` gains the same `factRefs` (STG-06 requires it):

```json
"factRefs": { "type": "array", "items": { "type": "string", "format": "uuid" } }
```

This single change gives STG-06 (`factRefs`), STG-07/08 (`derivedFromRefs`), STG-10
(`rivalsHypothesisRef`), STG-11 (`targetHypothesisRef`) and every `Statement` list (`factRefs`) the refs
the protocol requires. Required by EP-05; without it, "no unsupported inference" is unenforceable.

## 4. SCH-04 — `Variable` state fields (Wave 2A)

```json
"previousState": { "type": "string", "nullable": true },
"currentState": { "type": "string", "nullable": true },
"magnitude": { "type": "string", "nullable": true },
"whyItMatters": { "type": "string", "nullable": true },
"factRefs": { "type": "array", "items": { "type": "string", "format": "uuid" } }
```

`direction` already contains `UNKNOWN`; no enum change. Null `previousState` is the contract-level
representation of "unknown, and we did not guess".

## 5. SCH-05 — `ClaimType += UNKNOWN` (Wave 2A, breaking for readers)

```json
"ClaimType": { "type": "string", "enum": ["FACT", "INFERENCE", "HYPOTHESIS", "PREDICTION", "UNKNOWN"] }
```

Mandatory companion edits in the same change:

- `apps/web/src/lib/labels.ts`: add `UNKNOWN` to `claimLabel` and `claimGlyph` (exhaustive `Record`
  maps — build fails otherwise).
- `AnalysisResultValidator`: UNKNOWN is rejected in `facts`; UNKNOWN items may not be referenced as
  supporting evidence; UNKNOWN is exempt from the "must be INFERENCE" checks that apply to
  interpretations.
- Prompt guidance (TASK-03-owned prompt): state that UNKNOWN is a permitted terminal type.

## 6. SCH-06 — `Hypothesis` structure and status extension (Wave 2A fields / 2B transitions)

New schema:

```json
"ConfidenceBand": { "type": "string", "enum": ["VERY_LOW", "LOW", "MEDIUM", "HIGH", "VERY_HIGH"] }
```

`Hypothesis.status` becomes (existing members retained for stored payloads and marked deprecated):

```json
"enum": ["OPEN", "STRENGTHENING", "WEAKENING", "CONFIRMED", "REJECTED", "UNRESOLVED", "SUPPORTED", "CHALLENGED", "ARCHIVED"]
```

`Hypothesis.properties` gains:

```json
"supportingFactRefs": { "type": "array", "items": { "type": "string", "format": "uuid" } },
"supportingEvidenceRefs": { "type": "array", "items": { "type": "string", "format": "uuid" } },
"contradictingEvidenceRefs": { "type": "array", "items": { "type": "string", "format": "uuid" } },
"assumptions": { "type": "array", "items": { "type": "string" } },
"alternativeHypothesisRefs": { "type": "array", "items": { "type": "string", "format": "uuid" } },
"falsificationConditions": { "type": "array", "items": { "$ref": "#/components/schemas/Statement" } },
"confidenceBand": { "$ref": "#/components/schemas/ConfidenceBand" }
```

Companion edits: `hypothesisStatusLabel` in `labels.ts` must cover the new members (exhaustive
`Record`). Legacy→protocol mapping is in [EPISTEMIC_TYPES.md](../EPISTEMIC_TYPES.md) §6:
`SUPPORTED→STRENGTHENING`, `CHALLENGED→WEAKENING`, `ARCHIVED→UNRESOLVED`.

## 7. SCH-07 — predictions become part of the snapshot (Wave 2B, with migration)

`AnalysisResult.properties` gains:

```json
"predictions": { "type": "array", "items": { "$ref": "#/components/schemas/Prediction" } }
```

`Prediction.status` becomes:

```json
"enum": ["OPEN", "CONFIRMED", "REJECTED", "PARTIAL", "UNRESOLVED"]
```

`Prediction.properties` gains:

```json
"observable": { "type": "string", "nullable": true },
"whereToCheck": { "type": "string", "nullable": true },
"basisFactRefs": { "type": "array", "items": { "type": "string", "format": "uuid" } }
```

`Prediction.hypothesisId` keeps its name and now references the **local** hypothesis id of the same
snapshot (PR-01); persistence writes that id into `hypotheses`. Companion migration (SCH-14/M2)
relaxes the `predictions_status_check` constraint to include `OPEN`. Existing `HypothesisDetail`
readers keep working (server-side reads produce JSON, not records).

## 8. SCH-08 — verification plan fields on `Indicator` (Wave 2A)

```json
"whereToCheck": { "type": "string", "nullable": true },
"supportingResult": { "type": "string", "nullable": true },
"contradictingResult": { "type": "string", "nullable": true },
"priority": { "type": "string", "nullable": true, "enum": ["HIGH", "MEDIUM", "LOW", "UNKNOWN"] },
"deadline": { "type": "string", "format": "date-time", "nullable": true },
"hypothesisRef": { "type": "string", "format": "uuid", "nullable": true }
```

`name` is the protocol's `whatToCheck`; `measurement` and `frequency` already carry how and how often.
`hypothesisRef` lets a plan item serve a hypothesis that has no prediction, which the protocol allows
(STG-15).

## 9. SCH-09 — rubric fields on `ConfidenceAssessment` (Wave 2A)

New schema:

```json
"ConfidenceDimension": {
  "type": "object",
  "additionalProperties": false,
  "required": ["dimension", "level", "points"],
  "properties": {
    "dimension": {
      "type": "string",
      "enum": ["D1_SOURCE_QUALITY", "D2_EVIDENCE_DIRECTNESS", "D3_INDEPENDENT_CORROBORATION", "D4_MECHANISM_SUPPORT", "D5_COUNTER_EVIDENCE_RESILIENCE"]
    },
    "level": { "type": "integer", "minimum": 0, "maximum": 5 },
    "points": { "type": "integer", "minimum": 0, "maximum": 100 },
    "note": { "type": "string", "nullable": true }
  }
}
```

New schema:

```json
"ConfidenceMethod": { "type": "string", "enum": ["MODEL_JUDGMENT", "RUBRIC"] }
```

`ConfidenceAssessment.properties` gains:

```json
"band": { "$ref": "#/components/schemas/ConfidenceBand" },
"method": { "$ref": "#/components/schemas/ConfidenceMethod" },
"rubricVersion": { "type": "string", "nullable": true },
"dimensions": { "type": "array", "items": { "$ref": "#/components/schemas/ConfidenceDimension" } },
"advisoryScore": { "type": "integer", "minimum": 0, "maximum": 100, "nullable": true }
```

`score`, `reason` and `isProbability` stay required and unchanged, so existing readers and stored
payloads keep working. Adding components to the record does change its single canonical constructor, so
`MockModelGateway` must pass `null` for the new components in the same change — Java records have no
overloaded constructors, and this is exactly the rule of §0 item 5.
`advisoryScore` is the diagnostic slot for a model-supplied number; no reader may use it as the score.

## 10. SCH-10 — `CorroboratingSignal` (Wave 2A)

New schema, a superset of `Statement` so existing `Statement`-shaped consumers keep working:

```json
"CorroboratingSignal": {
  "type": "object",
  "additionalProperties": false,
  "required": ["type", "statement", "reasoning", "confidence", "sourceRefs", "hypothesisRef", "whereToCheck", "status"],
  "properties": {
    "type": { "$ref": "#/components/schemas/ClaimType" },
    "statement": { "type": "string", "minLength": 1, "maxLength": 20000 },
    "reasoning": { "type": "string", "minLength": 1, "maxLength": 20000 },
    "confidence": { "type": "integer", "minimum": 0, "maximum": 100 },
    "sourceRefs": { "type": "array", "items": { "$ref": "#/components/schemas/SourceRef" } },
    "hypothesisRef": { "type": "string", "format": "uuid" },
    "whereToCheck": { "type": "string", "minLength": 1, "maxLength": 20000 },
    "window": { "type": "string", "nullable": true },
    "status": { "type": "string", "enum": ["NOT_OBSERVED", "OBSERVED", "NOT_FOUND"] }
  }
}
```

`AnalysisResult.corroboratingSignals.items` changes `$ref` from `Statement` to `CorroboratingSignal`.
Because the new record contains every `Statement` field, `AnalysisView.tsx`'s `ClaimList` usage stays
type-compatible; the view can then add the new status/where fields.

## 11. SCH-11 — evidence traceability (Wave 2B)

`Evidence.properties` gains:

```json
"factRefs": { "type": "array", "items": { "type": "string", "format": "uuid" } },
"analysisId": { "type": "string", "format": "uuid", "nullable": true }
```

`analysisId` links evidence to the snapshot that introduced it; `factRefs` links it to the facts it
bears on. Both are needed by TASK-07's acceptance criteria (source FK + verification reason/timeline)
and by TASK-06's rubric-input updates.

## 12. SCH-12 — `CausalLink.supportLevel` (Wave 2A)

```json
"supportLevel": { "type": "string", "nullable": true, "enum": ["SUPPORTED", "PLAUSIBLE", "SPECULATIVE"] },
"factRefs": { "type": "array", "items": { "type": "string", "format": "uuid" } }
```

`cause`/`effect` are the protocol's `from`/`to` (documented mapping, no rename). Landing
`supportLevel` without `factRefs` would make SUPPORTED unverifiable, so both fields are in one change.
Absent `supportLevel` reads as `SPECULATIVE` (the conservative default).

## 13. SCH-13 — snapshot version and provenance (Wave 2A)

New schema:

```json
"Provenance": {
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "domainStrategyId": { "type": "string", "nullable": true },
    "rubricVersion": { "type": "string", "nullable": true },
    "promptVersions": { "type": "array", "items": { "type": "string" } },
    "modelRunIds": { "type": "array", "items": { "type": "string", "format": "uuid" } }
  }
}
```

`AnalysisResult.properties` gains:

```json
"protocolVersion": { "type": "string", "nullable": true },
"provenance": { "$ref": "#/components/schemas/Provenance", "nullable": true }
```

Legacy snapshots have neither field; that reads as "protocol version unknown", which is exactly true.
No model run id or prompt text is invented here: the values come from the audit records that already
exist (`model_runs`); raw prompts are never stored (AGENTS.md).

## 14. SCH-14 — persistence deltas (migrations, integrator-owned)

Not OpenAPI. Immutable, append-only Flyway migrations; numbers must be reserved with the integrator.

| id | migration | SQL sketch | wave |
| --- | --- | --- | --- |
| — | none | Wave 2A needs no schema change: all new snapshot fields ride in `analyses.payload` | 2A |
| M2 | V2 | `ALTER TABLE predictions DROP CONSTRAINT predictions_status_check; ALTER TABLE predictions ADD CONSTRAINT predictions_status_check CHECK (status IN ('OPEN','CONFIRMED','REJECTED','PARTIAL','UNRESOLVED'));` — constraint name must be verified from the live catalogue | 2B (TASK-07) |
| M3 | V3 | `ALTER TABLE hypotheses ADD COLUMN version integer NOT NULL DEFAULT 0;` for optimistic concurrency | 2B (TASK-06) |
| M4 | V4 | `ALTER TABLE evidence ADD COLUMN analysis_id uuid NULL REFERENCES analyses(id); ALTER TABLE predictions ADD COLUMN verified_at timestamptz NULL;` | 2B (TASK-07) |

`hypothesis_events`, `indicators` and `analyses` need no change.

## 15. Sequencing within Wave 2A

Applying SCH-01…SCH-06, SCH-08…SCH-10 and SCH-12…SCH-13 (eleven proposals) is **one integrator-led
change**. Order inside
the change:

1. Edit `contracts/openapi.yaml` (all of 2A at once).
2. `npm run contracts` → regenerate Java records, TS types, JSON schema, classpath copy.
3. Update handwritten call sites that break (see §17).
4. Update `labels.ts` for the new enum members (compile gate).
5. Extend `AnalysisResultValidator` with Gate B/C checks that are now expressible.
6. `./scripts/mvn.sh -f apps/api/pom.xml test`, `npm run contracts:check`, `npm run lint`,
   `npm run typecheck`, `npm run test`, `npm run build`.
7. TASK-03/TASK-05 rebase onto the new contract revision before starting their feature work.

Wave 2B applies SCH-07 and SCH-11 plus migrations M2–M4 in a second integrator change, after 2A is
merged, because TASK-07's prediction persistence depends on the `OPEN` status being accepted.

## 16. Backward and forward compatibility

| concern | handling |
| --- | --- |
| existing `analyses.payload` rows | read as-is; absent fields mean `MODEL_JUDGMENT`, `REPORTED`, `SPECULATIVE`, no refs, protocol version unknown |
| no backfill | historical payloads are never rewritten (PR-17); no data migration |
| generated Java | new optional fields become nullable record components; Jackson tolerates absence |
| TypeScript | new optional properties; exhaustive `Record`s are the only compile breaks and are fixed in the same change |
| OpenAI-compatible providers | response format stays `json_object`; the embedded schema text gains optional properties, which does not invalidate older outputs; local record validation is the gate |
| mock provider | must be updated in the same change; it is the reference fixture for the protocol |
| rollback | revert the contract change and regenerated artifacts; no migration in 2A, so rollback is clean. 2B migrations are additive and stay applied |

## 17. Blast radius (verified by inspection)

Files that must change together when 2A is applied:

| file | reason |
| --- | --- |
| `contracts/openapi.yaml` | source of truth |
| `apps/api/src/main/java/com/signalframe/contract/*.java` | regenerated (never hand-edited) |
| `apps/web/src/lib/api.generated.ts` | regenerated |
| `contracts/analysis-result.schema.json`, `apps/api/src/main/resources/analysis-result.schema.json` | regenerated |
| `apps/api/src/main/java/com/signalframe/infrastructure/ai/providers/MockModelGateway.java` | main-code constructor call site for the changed records, apart from `AnalysisPipeline`'s `Fact` |
| `apps/api/src/main/java/com/signalframe/analysis/application/AnalysisPipeline.java` | constructs `Fact` in the `ExtractFacts` scaffold step |
| `apps/api/src/main/java/com/signalframe/ai/application/AnalysisResultValidator.java` | new epistemic/ref/confidence gates |
| `apps/web/src/lib/labels.ts` | exhaustive maps for `ClaimType` and `Hypothesis["status"]` |
| `apps/web/src/components/AnalysisView.tsx` | optional: render new fields; no compile break thanks to the `CorroboratingSignal` superset |
| `apps/api/src/main/resources/prompts/synthesis/prompt.txt` | must describe the new optional artifacts; prompt version bump |

No test currently constructs the changed records directly (verified: only assertions on mock output in
`FoundationIntegrationTest`), so the test suite follows the mock provider.

## 18. Explicitly not proposed

- No `oneOf`/`anyOf`/`allOf` unions (generator limitation and an unnecessary complexity).
- No rename of `cause`/`effect`, `name`, `description`, or any existing field.
- No change to `Source`, `NewsItem`, `NewsInput`, `AnalysisJob`, `JobStatus`, `JobEvent`, `Topic`,
  `NewsValueScore`, `ModelRun` or `Metrics`.
- No new `DomainType` or `ModelPurpose` values.
- No new module, no new table for the snapshot, no change to module boundaries.
- No publisher reputation, trust score, or media-credit field, anywhere.
- No probability field, ever.
