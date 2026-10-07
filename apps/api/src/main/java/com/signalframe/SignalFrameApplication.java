package com.signalframe;

import com.signalframe.ai.application.AiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AiProperties.class)
public class SignalFrameApplication {

  public static void main(String[] args) {
    SpringApplication.run(SignalFrameApplication.class, args);
  }
}
