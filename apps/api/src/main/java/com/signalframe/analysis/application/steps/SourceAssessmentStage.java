package com.signalframe.analysis.application.steps;

import com.signalframe.analysis.application.steps.ProtocolModel.*;
import com.signalframe.contract.JobStatus;
import com.signalframe.contract.Source;
import java.net.URI;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * STG-01/STG-02: make the input explicit and describe what it can carry.
 *
 * <p>Purely deterministic (PR-14): the publisher comes from the URL host, the
 * completeness from the extraction status and text length, and independence is
 * {@code SINGLE_SOURCE} while the snapshot holds one source. Everything the
 * ingestion payload cannot justify — a publication date, primary/secondary
 * status, a document class — stays {@code UNKNOWN} with a written note. No
 * publisher reputation, no trust score and no inferred date is produced; media
 * credit leaderboards are explicitly out of scope (STG-02).
 */
@Component
public class SourceAssessmentStage implements ProtocolStage {

  static final String NAME = "SourceAssessment";
  static final String STEP = "NormalizeInput";

  /** Below this many characters the payload cannot be a complete article. */
  static final int COMPLETE_TEXT_CHARS = 400;

  @Override
  public PipelineState execute(PipelineState in) {
    Source source = in.news().source();
    if ("NEEDS_TEXT".equals(source.extractionStatus())) throw new StageFailure(
      NAME,
      StageFailure.Kind.UNSUPPORTED_RESULT,
      "the source has no usable text (extractionStatus NEEDS_TEXT), so it cannot be analyzed (STG-01)"
    );
    var notes = new ArrayList<String>();
    var publisher = publisherOf(source, notes);
    var completeness = completenessOf(source, notes);
    notes.add(
      "publishedAt is UNKNOWN: the ingestion payload does not carry a publication timestamp, and STG-02 forbids inferring it from context."
    );
    notes.add(
      "primaryOrSecondary is UNKNOWN: the text alone cannot establish whether this is a primary document or a report about one."
    );
    notes.add(
      "sourceType is UNKNOWN: the ingestion payload does not declare a document class."
    );
    notes.add(
      "independence is SINGLE_SOURCE: exactly one source is present in this snapshot."
    );
    var assessment = new SourceAssessment(
      SourceType.UNKNOWN,
      publisher,
      "UNKNOWN",
      SourceClass.UNKNOWN,
      Independence.SINGLE_SOURCE,
      completeness,
      notes
    );
    return in.withSourceAssessment(assessment).executed(NAME, null);
  }

  private static String publisherOf(Source source, List<String> notes) {
    String url = source.url();
    if (url == null || url.isBlank()) {
      notes.add(
        "publisher is UNKNOWN: the source was pasted without a URL, so no host identifies the publisher."
      );
      return "UNKNOWN";
    }
    try {
      String host = URI.create(url).getHost();
      if (host == null || host.isBlank()) {
        notes.add("publisher is UNKNOWN: the source URL has no host.");
        return "UNKNOWN";
      }
      return host.startsWith("www.") ? host.substring(4) : host;
    } catch (IllegalArgumentException malformed) {
      notes.add(
        "publisher is UNKNOWN: the stored source URL could not be parsed as a host."
      );
      return "UNKNOWN";
    }
  }

  private static Completeness completenessOf(Source source, List<String> notes) {
    String text = source.text();
    if (text == null || text.isBlank()) {
      notes.add("contentCompleteness is METADATA_ONLY: the source carries no text.");
      return Completeness.METADATA_ONLY;
    }
    if ("NEEDS_TEXT".equals(source.extractionStatus())) return Completeness.METADATA_ONLY;
    if (text.length() < COMPLETE_TEXT_CHARS) {
      notes.add(
        "contentCompleteness is PARTIAL: the stored text is shorter than " +
        COMPLETE_TEXT_CHARS +
        " characters, so a full article cannot be assumed."
      );
      return Completeness.PARTIAL;
    }
    return Completeness.COMPLETE;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public JobStatus status() {
    return JobStatus.NORMALIZING;
  }

  @Override
  public String durableStep() {
    return STEP;
  }
}
