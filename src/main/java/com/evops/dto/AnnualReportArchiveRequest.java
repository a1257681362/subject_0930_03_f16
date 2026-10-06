package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 年度报表归档请求（museum-16）。归档为终态，数字冻结。 */
@Data
public class AnnualReportArchiveRequest {

    @NotBlank(message = "操作人不能为空")
    private String operator;
}
