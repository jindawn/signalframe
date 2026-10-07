package com.signalframe.ai.domain;

import com.signalframe.contract.*;
import java.util.UUID;

public record ModelRequest(
  UUID jobId,
  ModelPurpose purpose,
  String promptVersion,
  String prompt,
  NewsItem news,
  ModelProfile profile,
  boolean repair
) {}
