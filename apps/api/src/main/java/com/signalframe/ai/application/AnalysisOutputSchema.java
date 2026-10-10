package com.signalframe.ai.application;

import com.signalframe.shared.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.core.io.ClassPathResource;

/**
 * The JSON Schema handed to the model, projected down to what the synthesis call
 * can actually produce.
 *
 * <p>The generated resource is the canonical OpenAPI vocabulary: 48 component
 * schemas covering every route in the API, pretty-printed, 53 804 characters.
 * Embedding it verbatim in the prompt was the single largest cost of a synthesis
 * call — measured at ~14.8 k prompt tokens for a 200-character news item — and
 * almost all of it was irrelevant: {@code NewsItem}, {@code AnalysisJob},
 * {@code ModelRun}, {@code Topic}, {@code ModelProfile} and 24 other schemas that
 * no synthesis output may ever contain.
 *
 * <p>This class therefore emits a <strong>deterministic projection</strong> of the
 * same generated resource:
 *
 * <ul>
 *   <li>only the schemas reachable from {@code AnalysisResult} by following
 *       {@code $ref} (19 of 48 today), computed by transitive closure rather than
 *       by a hand-maintained list, so adding a field to the contract cannot leave
 *       the prompt stale;</li>
 *   <li>compact serialization instead of the resource's 2-space indentation;</li>
 *   <li>{@code $comment} dropped (editorial metadata).</li>
 * </ul>
 *
 * <p>Nothing here is a second source of truth and nothing here is validated
 * against: the resource stays the generated artifact checked by
 * {@code npm run contracts:check}, and every value is still enforced by the
 * generated records, their bean-validation constraints and
 * {@link AnalysisResultValidator}. This class only decides what the model is
 * shown. If the projection ever failed to parse, the caller falls back to the raw
 * resource rather than sending a truncated schema.
 */
public final class AnalysisOutputSchema {

  /** The generated schema resource, also written by {@code scripts/generate-java.py}. */
  static final String RESOURCE = "analysis-result.schema.json";

  /** Root of the projection: the snapshot the synthesis call must return. */
  static final String ROOT = "AnalysisResult";

  private static final String REF_PREFIX = "#/components/schemas/";

  private AnalysisOutputSchema() {}

  /** The raw generated resource, exactly as it sits on the classpath. */
  public static String raw() {
    try {
      return new ClassPathResource(RESOURCE).getContentAsString(
        StandardCharsets.UTF_8
      );
    } catch (Exception e) {
      throw new IllegalStateException("Analysis schema resource missing", e);
    }
  }

  /**
   * The projected schema, or the raw resource if the projection cannot be built.
   * Deterministic for a given resource content, so the same snapshot always sees
   * the same prompt.
   */
  public static String render(JsonCodec json) {
    return render(json, raw());
  }

  /**
   * The projection of a schema document, or that document unchanged when it
   * cannot be parsed. Sending the raw resource is always safe — it is the
   * generated artifact — so a projection failure degrades the prompt rather than
   * breaking the analysis.
   */
  static String render(JsonCodec json, String rawSchema) {
    try {
      return project(json, rawSchema);
    } catch (RuntimeException malformed) {
      return rawSchema;
    }
  }

  /**
   * The projection for roots other than {@code AnalysisResult}: the same
   * deterministic closure and compaction, without the {@code allOf} wrapper,
   * which only makes sense when the reply is the root object itself.
   *
   * <p>Used by the two-stage synthesis path so each stage prompt carries a schema
   * that describes only the artifacts that stage may produce — still derived from
   * the one generated contract, so a contract change cannot leave a stage schema
   * stale.
   */
  public static String renderFor(JsonCodec json, Set<String> roots) {
    try {
      return project(json, raw(), roots);
    } catch (RuntimeException malformed) {
      return raw();
    }
  }

  static String project(
    JsonCodec json,
    String rawSchema,
    Set<String> roots
  ) {
    var document = json.read(rawSchema, Map.class);
    var components = asMap(document.get("components"));
    var schemas = asMap(components.get("schemas"));
    var reachable = new LinkedHashSet<String>();
    for (var root : roots) reachable.addAll(closure(schemas, root));

    // Preserve the resource's own component order so the prompt is stable.
    var kept = new LinkedHashMap<String, Object>();
    for (var entry : schemas.entrySet()) if (
      reachable.contains(entry.getKey())
    ) kept.put(entry.getKey(), entry.getValue());

    var projected = new LinkedHashMap<String, Object>();
    projected.put("$schema", document.get("$schema"));
    projected.put("components", Map.of("schemas", kept));
    return json.write(projected);
  }

  static String project(JsonCodec json, String rawSchema) {
    var document = json.read(rawSchema, Map.class);
    var components = asMap(document.get("components"));
    var schemas = asMap(components.get("schemas"));
    var reachable = closure(schemas, ROOT);

    // Preserve the resource's own component order so the prompt is stable.
    var kept = new LinkedHashMap<String, Object>();
    for (var entry : schemas.entrySet()) if (
      reachable.contains(entry.getKey())
    ) kept.put(entry.getKey(), entry.getValue());

    var projected = new LinkedHashMap<String, Object>();
    projected.put("$schema", document.get("$schema"));
    projected.put("allOf", document.get("allOf"));
    projected.put("components", Map.of("schemas", kept));
    return json.write(projected);
  }

  /** Component names reachable from {@code root}, following every {@code $ref}. */
  static Set<String> closure(Map<String, Object> schemas, String root) {
    var seen = new LinkedHashSet<String>();
    var stack = new ArrayDeque<String>();
    stack.push(root);
    while (!stack.isEmpty()) {
      var name = stack.pop();
      if (name == null || !seen.add(name)) continue;
      var schema = schemas.get(name);
      if (schema == null) continue;
      for (var next : refs(schema)) if (!seen.contains(next)) stack.push(next);
    }
    return seen;
  }

  private static Set<String> refs(Object node) {
    var out = new LinkedHashSet<String>();
    collectRefs(node, out);
    return out;
  }

  private static void collectRefs(Object node, Set<String> out) {
    if (node instanceof Map<?, ?> map) {
      for (var entry : map.entrySet()) {
        var key = String.valueOf(entry.getKey());
        if ("$ref".equals(key) && entry.getValue() instanceof String ref) {
          out.add(ref.startsWith(REF_PREFIX) ? ref.substring(REF_PREFIX.length()) : ref);
        } else {
          collectRefs(entry.getValue(), out);
        }
      }
    } else if (node instanceof List<?> list) {
      for (var item : list) collectRefs(item, out);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return value instanceof Map<?, ?> map
      ? (Map<String, Object>) map
      : Map.of();
  }
}
