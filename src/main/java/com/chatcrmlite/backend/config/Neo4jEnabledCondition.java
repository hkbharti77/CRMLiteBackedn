package com.chatcrmlite.backend.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * Neo4j beans only when URI + credentials are present (blank password would always auth-fail).
 */
public class Neo4jEnabledCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var env = context.getEnvironment();
        String uri = env.getProperty("neo4j.uri", "");
        String username = env.getProperty("neo4j.username", "");
        String password = env.getProperty("neo4j.password", "");
        return StringUtils.hasText(uri)
                && StringUtils.hasText(username)
                && StringUtils.hasText(password);
    }
}
