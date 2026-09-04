package com.chatcrmlite.backend.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;

@Configuration
@EnableScheduling
@EnableAsync
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
public class SchedulerConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime() // Use database time to avoid clock drift issues across nodes
                .build()
        );
    }

    /**
     * Bounded executor for campaign batch processing (@Async in CampaignMessageWorker).
     *
     * Sizing rationale (2-core EC2):
     * - core=2: at most 2 campaigns execute truly in parallel (one per core)
     * - max=3:  small burst headroom for brief spikes
     * - queue=20: buffer pending campaign IDs without spawning new threads
     * - CallerRunsPolicy: if queue full, the scheduler thread itself runs the batch
     *   rather than rejecting work or creating unbounded threads.
     *
     * Previously @Async used the default SimpleAsyncTaskExecutor which spawned one
     * new thread per campaign with no upper bound.
     */
    @Bean("campaignTaskExecutor")
    public org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor campaignTaskExecutor() {
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor exec =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(3);
        exec.setQueueCapacity(20);
        exec.setThreadNamePrefix("campaign-batch-");
        exec.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        exec.initialize();
        return exec;
    }
}
