package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 年度报表异常凭证（museum-16）：只追加，生成后不可变。
 *
 * <p>同一报表同一来源凭证只允许一条（DB 唯一约束兜底）；归档后补录核对若发现
 * 新凭证，只 INSERT 新行，已有凭证永不被覆盖或删除。撤回重提产生新报表后，
 * 凭证在新报表下重新固化，旧报表凭证随旧报表原样保留。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_annual_report_anomaly_evidence")
public class AnnualReportAnomalyEvidence extends BaseEntity {

    private Long reportId;

    private Integer reportYear;

    /** 报表内凭证序号，从 1 递增 */
    private Integer seqNo;

    /** EQUIPMENT_FAULT 修复设备故障 / ESCORT_ANOMALY 押运节点异常 */
    private String source;

    /** 来源凭证业务号（故障单号 FT-xxxxxx / 协议号#节点） */
    private String sourceNo;

    private String campusCode;

    private Long relicId;

    private Long projectId;

    private LocalDateTime anomalyAt;

    private String summary;

    private LocalDateTime recordedAt;
}
