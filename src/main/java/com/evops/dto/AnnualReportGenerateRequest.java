package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** 年度报表生成请求（museum-16）：按年份与当前生效口径版本生成。 */
@Data
public class AnnualReportGenerateRequest {

    @NotNull(message = "报表年份不能为空")
    private Integer year;

    @NotBlank(message = "操作人不能为空")
    private String operator;
}
