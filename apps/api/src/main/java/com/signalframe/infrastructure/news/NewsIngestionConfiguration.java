package com.signalframe.infrastructure.news;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers ingestion fetch properties. Kept here (instead of the shared
 * application class or {@code application.yml}) so the news task owns its
 * configuration without editing root configuration.
 */
@Configuration
@EnableConfigurationProperties(NewsFetchProperties.class)
public class NewsIngestionConfiguration {}
