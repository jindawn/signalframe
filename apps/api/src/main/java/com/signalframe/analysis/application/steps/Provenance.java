package com.signalframe.analysis.application.steps;

import java.util.*;

/**
 * Deterministic provenance trailer for the v0.1 snapshot.
 *
 * <p>The frozen v0.1 contract has no fields for `factRefs`, `supportLevel`,
 * `derivedFromRefs` or `rivalsHypothesisRef` (those are SCH-02/03/06/10). Until
 * the v0.2 contract lands, the pipeline keeps those refs auditable by appending a
 * machine-parsable trailer to the item's free-text field. It is a rendering of
 * refs that already exist inside the snapshot — never a new claim, never
 * invented, and never a substitute for the validation gates.
 *
 * <p>Format: {@code <text> | protocol: key=value; key=value}. Keys are rendered in
 * a fixed order, so the trailer is deterministic and a reader can recover the ids.
 */
final class Provenance {

  static final String MARKER = " | protocol: ";

  private Provenance() {}

  static String append(String text, LinkedHashMap<String, String> fields) {
    if (fields == null || fields.isEmpty()) return text;
    var parts = new ArrayList<String>();
    fields.forEach((key, value) -> {
      if (value == null || value.isBlank()) return;
      parts.add(key + "=" + value.replaceAll("\\s+", " ").trim());
    });
    if (parts.isEmpty()) return text;
    return (text == null ? "" : text) + MARKER + String.join("; ", parts);
  }

  static LinkedHashMap<String, String> fields() {
    return new LinkedHashMap<>();
  }

  static String ids(List<UUID> values) {
    if (values == null || values.isEmpty()) return "";
    return String.join(",", values.stream().map(UUID::toString).toList());
  }

  static String id(UUID value) {
    return value == null ? "" : value.toString();
  }

  static String texts(List<String> values) {
    if (values == null || values.isEmpty()) return "";
    return String.join(" / ", values.stream().map(v -> v.replace(";", ",")).toList());
  }
}
