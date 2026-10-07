package com.signalframe.infrastructure.ai.providers;

import com.signalframe.ai.domain.*;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class RoutingModelGateway implements ModelGateway {

  private final MockModelGateway mock;
  private final OpenAiCompatibleGateway live;

  public RoutingModelGateway(
    MockModelGateway mock,
    OpenAiCompatibleGateway live
  ) {
    this.mock = mock;
    this.live = live;
  }

  public ModelResponse call(ModelRequest r) {
    String key = System.getenv(r.profile().apiKeyEnv());
    if (
      r.profile().provider().equals("mock") || key == null || key.isBlank()
    ) return mock.call(r);
    return live.call(r);
  }
}
