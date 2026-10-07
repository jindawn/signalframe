package com.signalframe.ai.domain;

public interface ModelGateway {
  ModelResponse call(ModelRequest request);
}
