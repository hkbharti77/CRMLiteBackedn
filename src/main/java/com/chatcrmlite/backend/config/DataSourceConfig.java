package com.chatcrmlite.backend.config;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.boot.autoconfigure.quartz.QuartzDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Multi-Region Database Routing Configuration
 * 
 * This configures two data sources:
 * 1. Writer: Points to the Global Primary in US-East-1
 * 2. Reader: Points to the Regional Read Replica (e.g. EU-Central-1)
 * 
 * Transactional(readOnly = true) will be routed to the Reader.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.writer")
    public HikariDataSource writerDataSource(org.springframework.core.env.Environment env) {
        HikariDataSource ds = DataSourceBuilder.create().type(HikariDataSource.class).build();
        ds.setPoolName("HikariPool-Writer");
        ds.setConnectionTestQuery("SELECT 1");
        ds.setKeepaliveTime(15000);
        ds.setMaxLifetime(90000);
        ds.setIdleTimeout(45000);
        ds.setValidationTimeout(3000);
        ds.setConnectionTimeout(30000);
        ds.setMaximumPoolSize(25);
        ds.setMinimumIdle(5);
        ds.setLeakDetectionThreshold(60000);
        org.springframework.boot.context.properties.bind.Binder.get(env)
                .bind("spring.datasource.writer.hikari", org.springframework.boot.context.properties.bind.Bindable.ofInstance(ds));
        return ds;
    }

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.reader")
    public HikariDataSource readerDataSource(org.springframework.core.env.Environment env) {
        HikariDataSource ds = DataSourceBuilder.create().type(HikariDataSource.class).build();
        ds.setPoolName("HikariPool-Reader");
        ds.setConnectionTestQuery("SELECT 1");
        ds.setKeepaliveTime(15000);
        ds.setMaxLifetime(90000);
        ds.setIdleTimeout(45000);
        ds.setValidationTimeout(3000);
        ds.setConnectionTimeout(30000);
        ds.setMaximumPoolSize(15);
        ds.setMinimumIdle(3);
        ds.setLeakDetectionThreshold(60000);
        org.springframework.boot.context.properties.bind.Binder.get(env)
                .bind("spring.datasource.reader.hikari", org.springframework.boot.context.properties.bind.Bindable.ofInstance(ds));
        return ds;
    }

    @Bean
    @Primary
    public DataSource dataSource(
            @org.springframework.beans.factory.annotation.Qualifier("writerDataSource") DataSource writerDataSource,
            @org.springframework.beans.factory.annotation.Qualifier("readerDataSource") DataSource readerDataSource) {
        RoutingDataSource routingDataSource = new RoutingDataSource();
        Map<Object, Object> dataSourceMap = new HashMap<>();
        dataSourceMap.put(DataSourceType.WRITER, writerDataSource);
        dataSourceMap.put(DataSourceType.READER, readerDataSource);
        
        routingDataSource.setTargetDataSources(dataSourceMap);
        routingDataSource.setDefaultTargetDataSource(writerDataSource);
        return routingDataSource;
    }

    @Bean
    @QuartzDataSource
    public DataSource quartzDataSource(@org.springframework.beans.factory.annotation.Qualifier("writerDataSource") DataSource writerDataSource) {
        return writerDataSource;
    }

    /**
     * Custom Flyway on the writer datasource (Boot auto-config is disabled because of routing DS).
     * Migrations must finish before Hibernate touches the schema.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true", matchIfMissing = true)
    public org.flywaydb.core.Flyway flyway(
            @org.springframework.beans.factory.annotation.Qualifier("writerDataSource") DataSource writerDataSource) {
        return org.flywaydb.core.Flyway.configure()
                .dataSource(writerDataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .outOfOrder(true)
                .validateOnMigrate(false)
                .load();
    }

    /** Runs Flyway.migrate() during context init, before JPA (see depends-on post-processor below). */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true", matchIfMissing = true)
    public FlywayMigrationInitializer flywayInitializer(org.flywaydb.core.Flyway flyway) {
        return new FlywayMigrationInitializer(flyway);
    }

    /**
     * spring.flyway.enabled=false disables Boot's EMF→Flyway ordering; restore it so Hibernate
     * never creates tables (ddl-auto) before migrations that seed those tables.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true", matchIfMissing = true)
    public static BeanFactoryPostProcessor entityManagerFactoryDependsOnFlyway() {
        return beanFactory -> {
            for (String beanName : new String[]{"entityManagerFactory", "entityManagerFactoryBuilder"}) {
                if (!beanFactory.containsBeanDefinition(beanName)) {
                    continue;
                }
                BeanDefinition definition = beanFactory.getBeanDefinition(beanName);
                Set<String> dependsOn = new LinkedHashSet<>();
                if (definition.getDependsOn() != null) {
                    dependsOn.addAll(Arrays.asList(definition.getDependsOn()));
                }
                dependsOn.add("flywayInitializer");
                dependsOn.add("flyway");
                definition.setDependsOn(dependsOn.toArray(String[]::new));
            }
        };
    }

    public enum DataSourceType {
        WRITER, READER
    }

    public static class RoutingDataSource extends AbstractRoutingDataSource {
        @Override
        protected Object determineCurrentLookupKey() {
            return DataSourceContextHolder.getDataSourceType();
        }
    }

    public static class DataSourceContextHolder {
        private static final ThreadLocal<DataSourceType> contextHolder = new ThreadLocal<>();

        public static void setDataSourceType(DataSourceType type) {
            contextHolder.set(type);
        }

        public static DataSourceType getDataSourceType() {
            return contextHolder.get();
        }

        public static void clear() {
            contextHolder.remove();
        }
    }
}
