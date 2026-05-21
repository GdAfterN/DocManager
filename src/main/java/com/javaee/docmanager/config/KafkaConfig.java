package com.javaee.docmanager.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Configuration
public class KafkaConfig {

    @Bean
    public KafkaAdmin.NewTopics newTopics() {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name("user-register").partitions(1).replicas(1).build(),
                TopicBuilder.name("user-operate-log").partitions(1).replicas(1).build(),
                TopicBuilder.name("file-upload").partitions(1).replicas(1).build(),
                TopicBuilder.name("file-download").partitions(1).replicas(1).build(),
                TopicBuilder.name("file-delete").partitions(1).replicas(1).build(),
                TopicBuilder.name("cache-delete-retry").partitions(1).replicas(1).build()
        );
    }

    @Bean
    public KafkaAdmin kafkaAdmin() {
        Map<String, Object> configs = new HashMap<>();
        configs.put("bootstrap.servers", "localhost:9092");
        configs.put("request.timeout.ms", "3000");
        configs.put("default.api.timeout.ms", "5000");
        configs.put("connections.max.idle.ms", "5000");
        configs.put("metadata.max.age.ms", "5000");
        KafkaAdmin admin = new KafkaAdmin(configs);
        admin.setFatalIfBrokerNotAvailable(false);
        admin.setAutoCreate(false);
        return admin;
    }
}
