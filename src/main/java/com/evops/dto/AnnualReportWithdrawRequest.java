package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 未归档年度报表撤回请求（museum-16）。撤回后可重新提交新编号报表。 */
@Data
public class AnnualReportWithdrawRequest {

    @NotBlank(message = "操作人不能为空")
    private String operator;

    @NotBlank(message = "撤回原因不能为空")
    private String reason;
}
