package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 文物修复年度报表（museum-16）。
 *
 * <p>一年同时只允许一份在途（GENERATED/ARCHIVED）报表，由稀疏唯一键
 * {@code active_key} 兜底。未归档可撤回（WITHDRAWN）后重提——重提另出
 * 新编号报表，被撤回报表原样保留；归档（ARCHIVED）为终态，数字冻结，
 * 之后只能追加新快照（RECHECK），不可改写归档快照与凭证。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_annual_report")
public class AnnualReport extends BaseEntity {

    /** 报表编号 ARR-{year}-%04d，全库唯一；撤回重提另取新号 */
    private String reportNo;

    private Integer reportYear;

    private LocalDate periodStart;

    private LocalDate periodEnd;

    /** 固化口径版本 id（引用 + 字段冗余双轨） */
    private Long metricVersionId;

    private Integer metricVersionNo;

    private String metricVersionCode;

    /** GENERATED / ARCHIVED / WITHDRAWN */
    private String status;

    /** GENERATED/ARCHIVED = 'Y'+year；WITHDRAWN 置 NULL（稀疏唯一） */
    private String activeKey;

    /** 撤回重提时指向被撤回的原报表；首份为 null */
    private Long supersedesReportId;

    private Integer initCount;

    private Integer finishCount;

    /** 缺少验收资料（无验收通过成果报告）而标为待补的项目数 */
    private Integer pendingAcceptCount;

    private Integer loanCount;

    private Integer anomalyCount;

    private BigDecimal materialQty;

    private String generatedBy;

    private LocalDateTime generatedAt;

    private String archivedBy;

    private LocalDateTime archivedAt;

    private String withdrawnBy;

    private LocalDateTime withdrawnAt;

    private String withdrawReason;
}
