package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 年度报表定时生成任务日志（museum-16）：Quartz 每次触发（含手动触发）一行，
 * 成败全保留，不做更新与删除。
 *
 * <p>trigger_type: SCHEDULED Quartz 定时 / MANUAL 手动。
 * status: SUCCESS（含已存在在途报表的跳过）/ FAILED（同时产出一条告警）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_annual_report_job_log")
public class AnnualReportJobLog extends BaseEntity {

    /** Quartz fireInstanceId（手动触发为 MANUAL-+序号/时间），全库唯一 */
    private String fireInstanceId;

    private Integer reportYear;

    /** SCHEDULED / MANUAL */
    private String triggerType;

    /** SUCCESS / FAILED */
    private String status;

    private Long reportId;

    private String reportNo;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private String message;

    private String errorMessage;
}
