package com.javaee.docmanager.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 向量存储配置 — Qdrant REST 客户端
 */
@Configuration
public class VectorStoreConfig {

    @Value("${qdrant.host:localhost}")
    private String host;

    @Value("${qdrant.rest-port:6333}")
    private int restPort;

    @Bean
    public RestClient qdrantRestClient() {
        return RestClient.builder()
                .baseUrl("http://" + host + ":" + restPort)
                .build();
    }
}
