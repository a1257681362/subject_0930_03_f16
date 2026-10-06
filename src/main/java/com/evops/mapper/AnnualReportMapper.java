package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.AnnualReport;
import org.apache.ibatis.annotations.Select;

public interface AnnualReportMapper extends BaseMapper<AnnualReport> {

    @Select("SELECT NEXT VALUE FOR seq_annual_report_no")
    Long nextReportSeq();
}
