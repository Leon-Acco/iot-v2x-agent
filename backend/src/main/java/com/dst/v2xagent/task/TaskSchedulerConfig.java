package com.dst.v2xagent.task;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 任务卡 2.0 调度基建（项目首个主动调度设施，此前全部为 TTL 惰性缓存）。
 * 双线程池物理隔离：触发线程只做时间触发（毫秒级返回，绝不被业务阻塞），
 * 执行线程承接 Doris 查询（有界队列，满则拒绝——宁可记 SKIPPED 也不堆积拖垮触发）。
 */
@Configuration
@EnableScheduling
public class TaskSchedulerConfig {

    /** cron 触发线程池：只负责到点触发 */
    @Bean(name = "taskTriggerScheduler")
    public ThreadPoolTaskScheduler taskTriggerScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("task-cron-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        return scheduler;
    }

    /** 任务执行线程池：与触发线程隔离，Doris 慢查询不阻塞 cron */
    @Bean(name = "taskExecExecutor")
    public ThreadPoolTaskExecutor taskExecExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("task-exec-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler((r, e) -> {
            // 队列满直接拒绝：由提交方按 SKIPPED 记录，不无界堆积
            throw new org.springframework.core.task.TaskRejectedException("任务执行队列已满");
        });
        executor.initialize();
        return executor;
    }
}
