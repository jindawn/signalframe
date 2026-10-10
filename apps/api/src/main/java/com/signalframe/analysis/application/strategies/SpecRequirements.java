package com.signalframe.analysis.application.strategies;

import java.util.List;

/**
 * Construction guards shared by the immutable strategy spec records.
 *
 * <p>An empty strategy is not a valid recommendation set, so the guards make a blank or empty
 * template impossible to construct rather than leaving it to validation later.
 */
final class SpecRequirements {

  private SpecRequirements() {}

  static String text(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(
      field + " must not be blank"
    );
    return value.strip();
  }

  static <T> List<T> items(List<T> values, String field) {
    if (values == null || values.isEmpty()) throw new IllegalArgumentException(
      field + " must not be empty"
    );
    return List.copyOf(values);
  }

  static List<String> texts(List<String> values, String field) {
    List<String> copy = items(values, field);
    for (int i = 0; i < copy.size(); i++) text(
      copy.get(i),
      field + "[" + i + "]"
    );
    return copy;
  }
}
