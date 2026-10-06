package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 报表生成失败告警人工确认请求（museum-16）。 */
@Data
public class AnnualReportAlertAckRequest {

    @NotBlank(message = "确认人不能为空")
    private String acknowledgedBy;

    private String ackNote;
}
