package com.signalframe.analysis.application.strategies;

import com.signalframe.analysis.domain.DomainAnalysisStrategy;
import com.signalframe.contract.DomainType;
import org.springframework.stereotype.Component;

@Component
public class TechnologyAnalysisStrategy implements DomainAnalysisStrategy {

  public boolean supports(DomainType d) {
    return d == DomainType.TECH || d == DomainType.AI;
  }

  public String guidance() {
    return "Distinguish technical capability from adoption, deployment cost and independent reproducibility.";
  }
}
