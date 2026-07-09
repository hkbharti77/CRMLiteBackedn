package com.chatcrmlite.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.boot.autoconfigure.quartz.QuartzDataSource;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

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
    public DataSource writerDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.reader")
    public DataSource readerDataSource() {
        return DataSourceBuilder.create().build();
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
