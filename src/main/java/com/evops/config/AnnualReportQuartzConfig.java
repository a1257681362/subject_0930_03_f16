package com.evops.config;

import com.evops.schedule.AnnualReportGenerateJob;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.quartz.SchedulerFactoryBeanCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 年度报表 Quartz 调度装配（museum-16）。
 *
 * <p>把 Spring {@link ApplicationContext} 放入 Quartz SchedulerContext，
 * 使 Quartz 自行实例化的 {@link AnnualReportGenerateJob} 可取到受管服务；
 * 并按配置的 cron 注册年度生成 JobDetail 与 CronTrigger。可用
 * {@code museum.annual-report.quartz-enabled=false} 关闭自动注册（测试常用），
 * 关闭后仍可手动触发同一任务。</p>
 */
@Configuration
public class AnnualReportQuartzConfig {

    /** JobDetail / Trigger 标识 */
    public static final String JOB_NAME = "annualReportGenerateJob";
    public static final String TRIGGER_NAME = "annualReportGenerateTrigger";

    @Bean
    public SchedulerFactoryBeanCustomizer annualReportSchedulerContextCustomizer(
            ApplicationContext applicationContext) {
        return schedulerFactoryBean -> schedulerFactoryBean.setApplicationContextSchedulerContextKey(
                AnnualReportGenerateJob.APPLICATION_CONTEXT_KEY);
    }

    @Bean
    @ConditionalOnProperty(prefix = "museum.annual-report",
            name = "quartz-enabled", havingValue = "true", matchIfMissing = true)
    public JobDetail annualReportGenerateJobDetail() {
        return JobBuilder.newJob(AnnualReportGenerateJob.class)
                .withIdentity(JOB_NAME, "MUSEUM_ANNUAL_REPORT")
                .storeDurably()
                .withDescription("文物修复年度报表年度生成任务（统计上一完整自然年度）")
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "museum.annual-report",
            name = "quartz-enabled", havingValue = "true", matchIfMissing = true)
    public Trigger annualReportGenerateTrigger(JobDetail annualReportGenerateJobDetail,
                                               MuseumAnnualReportProperties properties) {
        CronScheduleBuilder cron = CronScheduleBuilder.cronSchedule(properties.getGenerateCron())
                .withMisfireHandlingInstructionDoNothing();
        return TriggerBuilder.newTrigger()
                .forJob(annualReportGenerateJobDetail)
                .withIdentity(TRIGGER_NAME, properties.getJobGroup())
                .withSchedule(cron)
                .withDescription("年度报表定时生成（默认每年 1 月 2 日 02:00）")
                .build();
    }
}
