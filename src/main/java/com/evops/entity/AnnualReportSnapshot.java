package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 年度报表快照（museum-16）：只追加，生成后不可变。
 *
 * <p>首版生成（GENERATED）、撤回后重提（RESUBMIT）、归档冻结（ARCHIVED）、
 * 归档后按原口径重新核对（RECHECK）各出一张；报表内 {@code seqNo} 递增。
 * 完整项目级统计结果（含馆区/密级分组与待补清单）存 {@code statsJson}。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_annual_report_snapshot")
public class AnnualReportSnapshot extends BaseEntity {

    /** 快照编号 ARS-%06d，全库唯一 */
    private String snapshotNo;

    private Long reportId;

    private String reportNo;

    private Integer reportYear;

    /** 报表内快照序号，从 1 递增 */
    private Integer seqNo;

    /** GENERATED / RESUBMIT / ARCHIVED / RECHECK */
    private String snapshotType;

    private Long metricVersionId;

    private Integer metricVersionNo;

    private String metricVersionCode;

    private Integer initCount;

    private Integer finishCount;

    private Integer pendingAcceptCount;

    private Integer loanCount;

    private Integer anomalyCount;

    private BigDecimal materialQty;

    /** 完整项目级统计结果 JSON（行集口径，导出裁剪以此为准） */
    private String statsJson;

    private String triggeredBy;

    private LocalDateTime triggeredAt;

    private String note;
}
