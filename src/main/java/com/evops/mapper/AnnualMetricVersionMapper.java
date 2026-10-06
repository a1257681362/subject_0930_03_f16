package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.AnnualMetricVersion;
import org.apache.ibatis.annotations.Select;

public interface AnnualMetricVersionMapper extends BaseMapper<AnnualMetricVersion> {

    @Select("SELECT NEXT VALUE FOR seq_annual_metric_version_no")
    Long nextMetricSeq();
}
