package com.signalframe.analysis.application.strategies;

import com.signalframe.analysis.domain.DomainAnalysisStrategy;
import com.signalframe.contract.DomainType;
import org.springframework.stereotype.Component;

@Component
public class DefaultDomainStrategy implements DomainAnalysisStrategy {

  public boolean supports(DomainType domain) {
    return true;
  }

  public String guidance() {
    return "Identify variables, distinguish claims from verification, and make uncertainty explicit.";
  }
}
