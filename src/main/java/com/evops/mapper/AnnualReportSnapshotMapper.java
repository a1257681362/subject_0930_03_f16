package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.AnnualReportSnapshot;
import org.apache.ibatis.annotations.Select;

public interface AnnualReportSnapshotMapper extends BaseMapper<AnnualReportSnapshot> {

    @Select("SELECT NEXT VALUE FOR seq_annual_snapshot_no")
    Long nextSnapshotSeq();
}
