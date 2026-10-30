package com.chatcrmlite.backend.config;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.boot.autoconfigure.quartz.QuartzDataSource;
import org.springframework.boot.autoconfigure.quartz.SchedulerFactoryBeanCustomizer;
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
        ds.setKeepaliveTime(30000);
        ds.setMaxLifetime(300000);
        ds.setIdleTimeout(60000);
        ds.setValidationTimeout(5000);
        ds.setConnectionTimeout(60000);
        ds.setMaximumPoolSize(15);
        ds.setMinimumIdle(2);
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
        ds.setKeepaliveTime(30000);
        ds.setMaxLifetime(300000);
        ds.setIdleTimeout(60000);
        ds.setValidationTimeout(5000);
        ds.setConnectionTimeout(60000);
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(2);
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
        ensureQuartzTablesExist(writerDataSource);
        return writerDataSource;
    }

    private void ensureQuartzTablesExist(DataSource dataSource) {
        org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(DataSourceConfig.class);
        try (java.sql.Connection conn = dataSource.getConnection()) {
            boolean tableExists = false;
            // 1. Check for PostgreSQL search_path resolution via to_regclass
            try (java.sql.PreparedStatement ps = conn.prepareStatement("SELECT to_regclass('qrtz_scheduler_state')")) {
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getString(1) != null) {
                        tableExists = true;
                    }
                }
            } catch (Exception e) {
                // Fallback for dialects without to_regclass (e.g. H2 in tests)
                try (java.sql.PreparedStatement ps = conn.prepareStatement(
                        "SELECT 1 FROM information_schema.tables WHERE LOWER(table_name) = 'qrtz_scheduler_state'")) {
                    try (java.sql.ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            tableExists = true;
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (!tableExists) {
                logger.info("Quartz scheduler state table not found; executing quartz-schema-postgres.sql to ensure Quartz tables exist...");
                org.springframework.core.io.Resource resource =
                        new org.springframework.core.io.ClassPathResource("quartz-schema-postgres.sql");
                if (!resource.exists()) {
                    resource = new org.springframework.core.io.ClassPathResource("schema.sql");
                }

                if (resource.exists()) {
                    executeQuartzDdlScript(conn, resource, logger);
                } else {
                    logger.warn("Neither quartz-schema-postgres.sql nor schema.sql found in classpath; cannot auto-create Quartz tables.");
                }
            } else {
                logger.info("Verified Quartz scheduler tables exist on writerDataSource.");
            }
        } catch (Exception e) {
            logger.error("Pre-startup check/initialization for Quartz tables encountered an error: {}", e.getMessage(), e);
        }
    }

    private void executeQuartzDdlScript(java.sql.Connection conn, org.springframework.core.io.Resource resource, org.slf4j.Logger logger) {
        try {
            String script;
            try (java.io.InputStream is = resource.getInputStream()) {
                script = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }

            String[] statements = script.split(";");
            boolean originalAutoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(true); // Isolate each DDL statement so one failure doesn't abort the connection in PostgreSQL
                for (String rawStmt : statements) {
                    String stmtSql = rawStmt.trim();
                    while (stmtSql.startsWith("--")) {
                        int newlineIdx = stmtSql.indexOf('\n');
                        if (newlineIdx == -1) {
                            stmtSql = "";
                            break;
                        }
                        stmtSql = stmtSql.substring(newlineIdx + 1).trim();
                    }
                    if (stmtSql.isEmpty()) {
                        continue;
                    }
                    if (stmtSql.toUpperCase().startsWith("CREATE EXTENSION")) {
                        continue;
                    }
                    try (java.sql.Statement s = conn.createStatement()) {
                        s.execute(stmtSql);
                    } catch (java.sql.SQLException ex) {
                        logger.warn("Notice executing Quartz DDL statement: {} (SQL: {})", ex.getMessage(),
                                stmtSql.length() > 60 ? stmtSql.substring(0, 60) + "..." : stmtSql);
                    }
                }
            } finally {
                try {
                    conn.setAutoCommit(originalAutoCommit);
                } catch (Exception ignored) {}
            }

            // Verify table actually exists now
            boolean verified = false;
            try (java.sql.Statement stmt = conn.createStatement()) {
                try (java.sql.ResultSet rs = stmt.executeQuery("SELECT 1 FROM qrtz_scheduler_state LIMIT 1")) {
                    verified = true;
                }
            } catch (Exception ex) {
                // Ignore query error, will check to_regclass
            }
            if (!verified) {
                try (java.sql.PreparedStatement ps = conn.prepareStatement("SELECT to_regclass('qrtz_scheduler_state')")) {
                    try (java.sql.ResultSet rs = ps.executeQuery()) {
                        if (rs.next() && rs.getString(1) != null) {
                            verified = true;
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (verified) {
                logger.info("Successfully verified and initialized Quartz tables on writerDataSource.");
            } else {
                logger.error("WARNING: Quartz tables DDL executed, but qrtz_scheduler_state table could not be verified on writerDataSource!");
            }
        } catch (Exception e) {
            logger.error("Failed to execute Quartz DDL script from {}: {}", resource.getFilename(), e.getMessage(), e);
        }
    }

    /**
     * Custom Flyway on the writer datasource (Boot auto-config is disabled because of routing DS).
     * Migrations must finish before Hibernate touches the schema.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true", matchIfMissing = true)
    public org.flywaydb.core.Flyway flyway(
            @org.springframework.beans.factory.annotation.Qualifier("writerDataSource") DataSource writerDataSource,
            org.springframework.core.env.Environment env) {
        // Read baseline-version and table from application.properties so a fresh DB baselines
        // at the correct version instead of Flyway's default "1".
        String baselineVersion = env.getProperty("spring.flyway.baseline-version", "10097");
        String table = env.getProperty("spring.flyway.table", "flyway_schema_history_3");
        String flywayUrl = env.getProperty("spring.flyway.url", env.getProperty("spring.datasource.writer.jdbc-url"));
        String flywayUser = env.getProperty("spring.flyway.user", env.getProperty("spring.datasource.writer.username"));
        String flywayPassword = env.getProperty("spring.flyway.password", env.getProperty("spring.datasource.writer.password"));

        var builder = org.flywaydb.core.Flyway.configure()
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion(baselineVersion)
                .table(table)
                .outOfOrder(true)
                .validateOnMigrate(false);

        if (flywayUrl != null && !flywayUrl.isBlank() && flywayUser != null && !flywayUser.isBlank()) {
            builder.dataSource(flywayUrl, flywayUser, flywayPassword);
        } else {
            builder.dataSource(writerDataSource);
        }

        return builder.load();
    }

    /** Runs Flyway repair + migrate during context init, before JPA (see depends-on post-processor below). */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true", matchIfMissing = true)
    public FlywayMigrationInitializer flywayInitializer(org.flywaydb.core.Flyway flyway) {
        return new FlywayMigrationInitializer(flyway, f -> {
            try {
                f.repair();
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(DataSourceConfig.class)
                        .warn("Flyway auto-repair notice: {}", e.getMessage());
            }
            f.migrate();
        });
    }

    /**
     * spring.flyway.enabled=false disables Boot's EMF→Flyway ordering; restore it so Hibernate
     * never creates tables (ddl-auto) before migrations that seed those tables.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true", matchIfMissing = true)
    public static BeanFactoryPostProcessor entityManagerFactoryDependsOnFlyway() {
        return beanFactory -> {
            Set<String> targetBeanNames = new LinkedHashSet<>(Arrays.asList(
                    "entityManagerFactory",
                    "entityManagerFactoryBuilder",
                    "quartzScheduler",
                    "schedulerFactoryBean"
            ));

            try {
                for (String name : beanFactory.getBeanNamesForType(org.quartz.Scheduler.class, true, false)) {
                    targetBeanNames.add(name);
                }
                for (String name : beanFactory.getBeanNamesForType(org.springframework.scheduling.quartz.SchedulerFactoryBean.class, true, false)) {
                    targetBeanNames.add(name);
                }
            } catch (Exception ignored) {}

            for (String beanName : targetBeanNames) {
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

    @Bean
    public SchedulerFactoryBeanCustomizer quartzSchedulerCustomizer() {
        return schedulerFactoryBean -> {
            // Give 5 seconds startup delay so Flyway and Hikari pools fully settle before Quartz starts polling triggers
            schedulerFactoryBean.setStartupDelay(5);
            schedulerFactoryBean.setWaitForJobsToCompleteOnShutdown(true);
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
