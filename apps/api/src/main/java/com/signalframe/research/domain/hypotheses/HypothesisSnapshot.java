package com.signalframe.research.domain.hypotheses;

import com.signalframe.contract.AnalysisResult;
import java.util.UUID;

/**
 * The immutable snapshot a hypothesis came from, plus the source that snapshot
 * itself was built on.
 *
 * <p>The snapshot is what makes hypothesis-scope confidence recomputable
 * (CONFIDENCE_MODEL §9, CF-03): facts, mechanisms, counter-arguments, signals and
 * the verification plan all live in {@code analyses.payload} and are read back as
 * the generated contract records. Nothing here is rewritten — PR-17 keeps the
 * stored snapshot immutable, and a transition only ever appends a research event.
 *
 * <p>{@code sourceId} is the source of the news item the snapshot analysed. It is
 * what separates independent corroboration from a second copy of the same origin:
 * an evidence row pointing at this source is a non-independent addition (D3 level
 * 1 or below), while one pointing elsewhere is independent (D3 levels 2-5).
 */
public record HypothesisSnapshot(AnalysisResult result, UUID sourceId) {}
