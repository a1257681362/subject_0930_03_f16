package com.evops.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 年度报表定时生成配置（museum-16）。
 *
 * <p>由 Quartz CronTrigger 驱动；cron 默认每年 1 月 2 日 02:00 触发，
 * 生成"上一完整自然年度"的报表。测试可关闭自动注册后手动触发同一 Job。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "museum.annual-report")
public class MuseumAnnualReportProperties {

    /** 是否向 Quartz 注册年度生成触发器（关闭后仍可手动触发/调用 Job） */
    private boolean quartzEnabled = true;

    /** Quartz cron 表达式，默认每年 1 月 2 日 02:00:00 */
    private String generateCron = "0 0 2 2 1 ?";

    /** 触发器/任务在 Quartz 中的分组 */
    private String jobGroup = "MUSEUM_ANNUAL_REPORT";
}
