package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 年度报表生成失败告警（museum-16）：一条失败任务日志对应一条，只追加、只提示。
 *
 * <p>Quartz 定时生成失败时同轮产出一条 {@code OPEN} 告警并保留任务日志，
 * 失败不伪装成功；告警由人工 {@code ACKNOWLEDGED} 关闭，不自动改变报表状态。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_annual_report_alert")
public class AnnualReportAlert extends BaseEntity {

    /** 告警单号 ARA-%06d，全库唯一 */
    private String alertNo;

    private Long jobLogId;

    private String fireInstanceId;

    private Integer reportYear;

    /** GENERATE_FAILED */
    private String alertType;

    /** OPEN / ACKNOWLEDGED */
    private String status;

    private String message;

    private LocalDateTime raisedAt;

    private String acknowledgedBy;

    private LocalDateTime acknowledgedAt;

    private String ackNote;
}
