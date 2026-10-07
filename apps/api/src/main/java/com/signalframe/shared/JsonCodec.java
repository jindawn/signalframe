package com.signalframe.shared;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class JsonCodec {

  private final JsonMapper mapper = JsonMapper.builder()
    .findAndAddModules()
    .enable(
      tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES
    )
    .build();

  public String write(Object value) {
    return mapper.writeValueAsString(value);
  }

  public <T> T read(String value, Class<T> type) {
    return mapper.readValue(value, type);
  }
}
