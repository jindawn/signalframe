package com.signalframe.shared;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resolves the offsets of a source span against the text the span is quoted from.
 *
 * <p>The protocol asks a model for two things at once: the exact quoted span, and its
 * position as UTF-16 code units. Measured on a 609-character Chinese news item, the
 * model copies the quote verbatim every time (19 of 19 spans in a recorded reply) and
 * gets the position wrong every time (19 of 19, off by one or two characters with no
 * pattern). The quote is the evidence; the offsets are bookkeeping. This resolves the
 * bookkeeping against the evidence instead of failing the whole analysis on it.
 *
 * <p>The rule it implements is deliberately narrow:
 *
 * <ul>
 *   <li>a quote that occurs verbatim in the source text has its offsets derived from
 *       where it occurs, so the stored span is exact by construction;
 *   <li>a quote that occurs more than once uses the occurrence closest to the position
 *       the model claimed, which keeps the choice deterministic and honours the model's
 *       intent when the same phrase appears twice;
 *   <li>a quote that does not occur verbatim is left exactly as the model wrote it, so
 *       the protocol check still rejects it — this never turns a fabricated quote into
 *       an accepted one.
 * </ul>
 *
 * <p>A reply that cannot be read is returned unchanged: the caller's parser is the
 * thing that reports malformed output, and this must never make a reply worse.
 */
public final class SourceSpanResolver {

  private static final JsonMapper MAPPER = JsonMapper.builder()
    .findAndAddModules()
    .build();

  private static final String SOURCE_REFS = "sourceRefs";
  private static final String QUOTE = "quote";
  private static final String START = "startOffset";
  private static final String END = "endOffset";

  private SourceSpanResolver() {}

  /** The reply with every resolvable source span's offsets derived from the source. */
  public static String resolve(String raw, String sourceText) {
    if (
      raw == null || raw.isBlank() || sourceText == null || sourceText.isEmpty()
    ) return raw;
    Object tree;
    try {
      tree = read(raw);
    } catch (RuntimeException notReadable) {
      return raw;
    }
    if (!(tree instanceof Map) && !(tree instanceof List)) return raw;
    try {
      return rewrite(tree, sourceText) ? MAPPER.writeValueAsString(tree) : raw;
    } catch (RuntimeException notWritable) {
      return raw;
    }
  }

  private static Object read(String raw) {
    try {
      return MAPPER.readValue(raw, Object.class);
    } catch (RuntimeException first) {
      // A reply may arrive wrapped in prose or a fenced block; the caller's parser
      // tolerates that, so this has to reach the same object it will.
      int from = raw.indexOf('{');
      int to = raw.lastIndexOf('}');
      if (from < 0 || to <= from) throw first;
      return MAPPER.readValue(raw.substring(from, to + 1), Object.class);
    }
  }

  private static boolean rewrite(Object node, String sourceText) {
    boolean changed = false;
    if (node instanceof Map<?, ?> map) {
      for (var value : map.values()) changed |= rewrite(value, sourceText);
      if (map.get(SOURCE_REFS) instanceof List<?> refs) {
        for (var ref : refs) if (ref instanceof Map<?, ?> item) changed |=
          resolve(item, sourceText);
      }
    } else if (node instanceof List<?> list) {
      for (var item : list) changed |= rewrite(item, sourceText);
    }
    return changed;
  }

  private static boolean resolve(Map<?, ?> ref, String sourceText) {
    if (!(ref.get(QUOTE) instanceof String quote) || quote.isEmpty()) return false;
    int claimed = ref.get(START) instanceof Number number
      ? number.intValue()
      : -1;
    int at = locate(sourceText, quote, claimed);
    if (at < 0) return false;
    @SuppressWarnings("unchecked")
    var writable = (Map<String, Object>) ref;
    boolean changed =
      !Integer.valueOf(at).equals(writable.get(START)) ||
      !Integer.valueOf(at + quote.length()).equals(writable.get(END));
    writable.put(START, at);
    writable.put(END, at + quote.length());
    return changed;
  }

  private static int locate(String sourceText, String quote, int claimed) {
    int first = sourceText.indexOf(quote);
    if (first < 0) return -1;
    if (claimed <= first) return first;
    int best = first;
    for (
      int at = sourceText.indexOf(quote, first + 1);
      at >= 0;
      at = sourceText.indexOf(quote, at + 1)
    ) if (Math.abs(at - claimed) < Math.abs(best - claimed)) best = at;
    return best;
  }
}
