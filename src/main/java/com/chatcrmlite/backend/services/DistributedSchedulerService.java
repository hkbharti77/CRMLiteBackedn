package com.chatcrmlite.backend.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Map;

/**
 * Robust, distributed-safe scheduler service using Quartz.
 * Supports persistence, clustering, and dynamic job management.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DistributedSchedulerService {

    private final Scheduler scheduler;

    /**
     * Schedules a one-time job to be executed at a specific date.
     * Persisted in DB and executed by any available cluster node.
     */
    public void scheduleOneTimeJob(String jobName, Class<? extends Job> jobClass, Date startAt, Map<String, Object> data) {
        JobDataMap jobDataMap = new JobDataMap();
        if (data != null) {
            jobDataMap.putAll(data);
        }

        JobDetail jobDetail = JobBuilder.newJob(jobClass)
                .withIdentity(jobName, "distributed-jobs")
                .usingJobData(jobDataMap)
                .storeDurably()
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(jobName + "-trigger", "distributed-triggers")
                .startAt(startAt)
                .withSchedule(SimpleScheduleBuilder.simpleSchedule().withMisfireHandlingInstructionFireNow())
                .build();

        try {
            if (scheduler.checkExists(jobDetail.getKey())) {
                scheduler.deleteJob(jobDetail.getKey());
            }
            scheduler.scheduleJob(jobDetail, trigger);
            log.info("Successfully scheduled distributed job {} to run at {}", jobName, startAt);
        } catch (SchedulerException e) {
            log.error("CRITICAL: Failed to schedule distributed job {}", jobName, e);
            throw new RuntimeException("Job scheduling failure", e);
        }
    }

    /**
     * Schedules a recurring job using a CRON expression.
     */
    public void scheduleCronJob(String jobName, Class<? extends Job> jobClass, String cronExpression) {
        JobDetail jobDetail = JobBuilder.newJob(jobClass)
                .withIdentity(jobName, "cron-jobs")
                .storeDurably()
                .build();

        CronTrigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(jobName + "-trigger", "cron-triggers")
                .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression))
                .build();

        try {
            if (scheduler.checkExists(jobDetail.getKey())) {
                scheduler.deleteJob(jobDetail.getKey());
            }
            scheduler.scheduleJob(jobDetail, trigger);
            log.info("Successfully scheduled distributed cron job {} with expression {}", jobName, cronExpression);
        } catch (SchedulerException e) {
            log.error("Failed to schedule distributed cron job {}", jobName, e);
        }
    }

    public void cancelJob(String jobName, String groupName) {
        try {
            scheduler.deleteJob(new JobKey(jobName, groupName));
        } catch (SchedulerException e) {
            log.warn("Failed to cancel job {}: {}", jobName, e.getMessage());
        }
    }
}
