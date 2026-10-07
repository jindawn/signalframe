package com.signalframe.analysis.domain;

import com.signalframe.contract.DomainType;

public interface DomainAnalysisStrategy {
  boolean supports(DomainType domain);
  String guidance();
}
