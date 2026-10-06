package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 指标口径版本登记/发布请求（museum-16）。
 * 各口径字段可选，缺省沿用系统默认口径；publish=true 时直接发布生效。
 */
@Data
public class MetricVersionCreateRequest {

    /** 立项口径，缺省 PROJECT_CREATE_TIME */
    private String projectInitBasis;

    /** 完成口径，缺省 PROJECT_FINISH_DATE */
    private String projectFinishBasis;

    /** 计入完成的项目状态，逗号分隔，缺省 REPAIRED,ACCEPTED */
    private String projectFinishStatuses;

    /** 材料消耗口径，缺省 ACTUAL_MINUS_RETURN（实际领用净额，扣退料） */
    private String materialConsumeBasis;

    /** 借展口径，缺省 LOAN_AGREEMENT_SIGNED */
    private String loanBasis;

    /** 异常事件来源，逗号分隔，缺省 EQUIPMENT_FAULT,ESCORT_ANOMALY */
    private String anomalySources;

    /** 验收资料口径，缺省 ACCEPT_REPORT_PASSED */
    private String acceptanceBasis;

    /** 变更/发布说明，发布新版本时建议填写，便于日后区分各版口径 */
    private String changeReason;

    @NotBlank(message = "操作人不能为空")
    private String operator;

    /** 是否登记后直接发布；false 仅存 DRAFT，需另行发布 */
    private Boolean publish;
}
