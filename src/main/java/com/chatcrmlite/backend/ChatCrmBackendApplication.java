package com.chatcrmlite.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.neo4j.Neo4jDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.neo4j.Neo4jReactiveDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.neo4j.Neo4jReactiveRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.data.neo4j.Neo4jRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.neo4j.Neo4jAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Neo4j auto-config (including reactive) is excluded so VECTOR mode works without Neo4j.
 * GraphConfig creates the driver only when neo4j.uri is set.
 */
@SpringBootApplication(exclude = {
        Neo4jAutoConfiguration.class,
        Neo4jDataAutoConfiguration.class,
        Neo4jRepositoriesAutoConfiguration.class,
        Neo4jReactiveDataAutoConfiguration.class,
        Neo4jReactiveRepositoriesAutoConfiguration.class
})
@EnableScheduling
@EnableCaching
@EnableAsync
public class ChatCrmBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChatCrmBackendApplication.class, args);
    }
}
