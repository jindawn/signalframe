package com.signalframe.ai.application.observability;

import com.signalframe.ai.domain.observability.*;
import com.signalframe.contract.ModelRun;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Aggregates the model run audit without collecting prompts or secrets.
 *
 * <p>Aggregation is a pure function of the persisted {@link ModelRun} records,
 * so it is unit-testable without a database. Unknown usage or cost stays null
 * and is counted separately; a partial total is never presented as complete.
 */
@Service
public class ModelObservabilityService {

  /** Rows fetched in one report unless overridden by configuration. */
  public static final int DEFAULT_ROW_LIMIT = 10_000;

  private final ModelObservabilityQuery query;
  private final int rowLimit;

  @Autowired
  public ModelObservabilityService(
    ModelObservabilityQuery query,
    @Value("${ai.observability.max-rows:10000}") int rowLimit
  ) {
    if (rowLimit < 1) throw new IllegalArgumentException(
      "Observability row limit must be at least 1"
    );
    this.query = query;
    this.rowLimit = rowLimit;
  }

  public ModelObservabilityService(ModelObservabilityQuery query) {
    this(query, DEFAULT_ROW_LIMIT);
  }

  /** Full report for one selection and grouping. */
  public ModelObservabilityReport report(
    ModelRunFilter filter,
    ModelRunGrouping grouping
  ) {
    var runs = ordered(query.runs(filter, rowLimit));
    return new ModelObservabilityReport(
      filter,
      grouping,
      Instant.now(),
      rowLimit,
      runs.size() >= rowLimit,
      summary(runs),
      groups(runs, grouping)
    );
  }

  /**
   * Totals across the selection.
   *
   * <p>Like {@link #report(ModelRunFilter, ModelRunGrouping)}, this reads at most
   * the configured row limit and cannot report whether the window was complete.
   * Use the report when completeness matters.
   */
  public ModelRunSummary summary(ModelRunFilter filter) {
    return summary(ordered(query.runs(filter, rowLimit)));
  }

  /**
   * Grouped metrics for the selection.
   *
   * <p>Reads the same bounded window as {@link #report}; only the report exposes
   * whether that window was truncated.
   */
  public List<ModelRunAggregate> aggregates(
    ModelRunFilter filter,
    ModelRunGrouping grouping
  ) {
    return groups(ordered(query.runs(filter, rowLimit)), grouping);
  }

  private static List<ModelRun> ordered(List<ModelRun> runs) {
    var copy = new ArrayList<>(runs);
    copy.sort(
      Comparator.comparing(ModelRun::startedAt).thenComparing(r -> r.id().toString())
    );
    return copy;
  }

  private static ModelRunSummary summary(List<ModelRun> runs) {
    var totals = new Totals();
    runs.forEach(totals::add);
    return new ModelRunSummary(
      totals.runs,
      totals.succeeded,
      totals.failed,
      countRetries(runs),
      totals.jobs.size(),
      totals.unknownUsageRuns,
      totals.unknownCostRuns,
      totals.inputKnown == 0 ? null : totals.inputTokens,
      totals.outputKnown == 0 ? null : totals.outputTokens,
      totals.totalKnown == 0 ? null : totals.totalTokens,
      totals.costKnown == 0 ? null : totals.estimatedCost,
      totals.latencyMsTotal,
      totals.latencyMsMax
    );
  }

  private static List<ModelRunAggregate> groups(
    List<ModelRun> runs,
    ModelRunGrouping grouping
  ) {
    var buckets = new LinkedHashMap<List<String>, List<ModelRun>>();
    for (var run : runs) buckets
      .computeIfAbsent(groupKey(run, grouping), key -> new ArrayList<>())
      .add(run);
    var result = new ArrayList<ModelRunAggregate>(buckets.size());
    for (var entry : buckets.entrySet()) {
      var bucket = entry.getValue();
      var totals = new Totals();
      bucket.forEach(totals::add);
      result.add(
        new ModelRunAggregate(
          groupLabel(grouping, bucket.getFirst()),
          shared(
            bucket.stream().map(ModelRun::provider).distinct().toList()
          ),
          shared(bucket.stream().map(ModelRun::model).distinct().toList()),
          grouping == ModelRunGrouping.PURPOSE ? bucket.getFirst().purpose() : null,
          grouping == ModelRunGrouping.PROMPT_VERSION
            ? bucket.getFirst().promptVersion()
            : null,
          grouping == ModelRunGrouping.JOB ? bucket.getFirst().jobId() : null,
          totals.runs,
          totals.succeeded,
          totals.failed,
          countRetries(bucket),
          totals.unknownUsageRuns,
          totals.unknownCostRuns,
          totals.inputKnown == 0 ? null : totals.inputTokens,
          totals.outputKnown == 0 ? null : totals.outputTokens,
          totals.totalKnown == 0 ? null : totals.totalTokens,
          totals.costKnown == 0 ? null : totals.estimatedCost,
          totals.latencyMsTotal,
          totals.latencyMsMax
        )
      );
    }
    result.sort(
      Comparator.comparing(
        ModelRunAggregate::group,
        Comparator.nullsFirst(Comparator.naturalOrder())
      )
    );
    return List.copyOf(result);
  }

  private static String shared(List<String> values) {
    return values.size() == 1 ? values.getFirst() : null;
  }

  /**
   * Identity of a group is the tuple of dimension values, never a joined string,
   * so two different identifiers can never share one bucket. Nulls are tolerated
   * because the service is a pure function over whatever was persisted.
   */
  private static List<String> groupKey(ModelRun run, ModelRunGrouping grouping) {
    return switch (grouping) {
      case PROVIDER_MODEL -> Arrays.asList(run.provider(), run.model());
      case PURPOSE -> Collections.singletonList(run.purpose().name());
      case PROMPT_VERSION -> Collections.singletonList(run.promptVersion());
      case JOB -> Collections.singletonList(String.valueOf(run.jobId()));
    };
  }

  private static String groupLabel(ModelRunGrouping grouping, ModelRun run) {
    return switch (grouping) {
      case PROVIDER_MODEL -> run.provider() + " / " + run.model();
      case PURPOSE -> run.purpose().name();
      case PROMPT_VERSION -> run.promptVersion();
      case JOB -> String.valueOf(run.jobId());
    };
  }

  /**
   * A retry is an attempt that is not the first one for the same job, purpose,
   * prompt version and recorded provider/model. A call that switched profile is
   * a different call configuration, not a retry of the previous one.
   */
  private static long countRetries(List<ModelRun> runs) {
    var seen = new HashMap<String, Integer>();
    long retries = 0;
    for (var run : runs) {
      String chain =
        run.jobId() +
        "|" +
        run.purpose() +
        "|" +
        run.promptVersion() +
        "|" +
        run.provider() +
        "|" +
        run.model();
      if (seen.merge(chain, 1, Integer::sum) > 1) retries++;
    }
    return retries;
  }

  private static final class Totals {

    long runs;
    long succeeded;
    long failed;
    long unknownUsageRuns;
    long unknownCostRuns;
    long inputTokens;
    long outputTokens;
    long totalTokens;
    long inputKnown;
    long outputKnown;
    long totalKnown;
    long costKnown;
    double estimatedCost;
    long latencyMsTotal;
    long latencyMsMax;
    final Set<UUID> jobs = new HashSet<>();

    void add(ModelRun run) {
      runs++;
      if ("SUCCEEDED".equals(run.status())) succeeded++; else failed++;
      if (run.jobId() != null) jobs.add(run.jobId());
      if (run.inputTokens() == null || run.outputTokens() == null || run.totalTokens() == null) unknownUsageRuns++;
      if (run.inputTokens() != null) {
        inputTokens += run.inputTokens();
        inputKnown++;
      }
      if (run.outputTokens() != null) {
        outputTokens += run.outputTokens();
        outputKnown++;
      }
      if (run.totalTokens() != null) {
        totalTokens += run.totalTokens();
        totalKnown++;
      }
      if (run.estimatedCost() == null) unknownCostRuns++; else {
        estimatedCost += run.estimatedCost();
        costKnown++;
      }
      long latency = run.latencyMs() == null ? 0L : run.latencyMs();
      latencyMsTotal += latency;
      latencyMsMax = Math.max(latencyMsMax, latency);
    }
  }
}
