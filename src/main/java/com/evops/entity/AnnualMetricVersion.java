package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 年度报表指标口径版本（museum-16）：全库顺序递增，只追加。
 *
 * <p>固化立项、完成、材料消耗、借展、异常事件、验收资料六项指标的统计口径。
 * 发布新版本时旧版本置 {@code SUPERSEDED} 且各口径字段永不改写；报表生成时
 * 把口径版本号与各口径冗余拷贝到报表与快照，因此即使之后口径再发布新版本，
 * 已生成（含在途核对中）的报表仍始终按原版本完成核对。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_annual_metric_version")
public class AnnualMetricVersion extends BaseEntity {

    /** 口径版本业务单号 AMV-000001，全库唯一 */
    private String metricVersionNo;

    /** 版本号，全库从 1 递增 */
    private Integer versionNo;

    /** 立项口径，默认 PROJECT_CREATE_TIME（按立项创建时间落年度） */
    private String projectInitBasis;

    /** 完成口径，默认 PROJECT_FINISH_DATE（按修复完成日落年度） */
    private String projectFinishBasis;

    /** 计入完成的项目状态（逗号分隔，默认 REPAIRED,ACCEPTED） */
    private String projectFinishStatuses;

    /** 材料消耗口径，默认 ACTUAL_MINUS_RETURN（实际领用净额，扣退料） */
    private String materialConsumeBasis;

    /** 借展口径，默认 LOAN_AGREEMENT_SIGNED（按借展协议签署次数） */
    private String loanBasis;

    /** 异常事件来源（逗号分隔）：EQUIPMENT_FAULT / ESCORT_ANOMALY */
    private String anomalySources;

    /** 验收资料口径，默认 ACCEPT_REPORT_PASSED（无验收通过成果报告即待补） */
    private String acceptanceBasis;

    /** DRAFT / ACTIVE / SUPERSEDED */
    private String status;

    private String changeReason;

    private String publishedBy;

    private LocalDateTime publishedAt;
}
