package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.entity.AnnualReport;
import com.evops.entity.AnnualReportAlert;
import com.evops.entity.AnnualReportJobLog;
import com.evops.mapper.AnnualReportAlertMapper;
import com.evops.mapper.AnnualReportJobLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.evops.constant.MuseumConstants.*;

/**
 * 年度报表生成任务编排（museum-16）：包裹 Quartz/手动触发生成，
 * 每次执行落一条任务日志（成败全保留），失败另出一条 OPEN 告警。
 *
 * <p>业务生成在 {@link AnnualReportService} 独立事务内；失败回滚业务数据后，
 * 任务日志与告警在本服务直接逐条落库（独立提交），因此<b>失败现场与堆栈必定保留</b>，
 * 不随业务事务回滚，也绝不把失败伪装成成功。</p>
 */
@Service
public class AnnualReportJobService {

    private static final Logger log = LoggerFactory.getLogger(AnnualReportJobService.class);

    private final AnnualReportService reportService;
    private final AnnualReportJobLogMapper jobLogMapper;
    private final AnnualReportAlertMapper alertMapper;
    private final AtomicLong manualSeq = new AtomicLong(0);

    public AnnualReportJobService(AnnualReportService reportService,
                                  AnnualReportJobLogMapper jobLogMapper,
                                  AnnualReportAlertMapper alertMapper) {
        this.reportService = reportService;
        this.jobLogMapper = jobLogMapper;
        this.alertMapper = alertMapper;
    }

    /** 一次生成执行结果（供调度器/接口返回，不抛异常，失败已落日志+告警）。 */
    public static class RunResult {
        public boolean success;
        public AnnualReport report;
        public AnnualReportJobLog jobLog;
        public AnnualReportAlert alert;
        public String errorMessage;
    }

    /**
     * 执行一次年度报表生成。
     *
     * @param fireInstanceId Quartz 触发实例 id（手动触发传 null，自动生成）
     */
    public RunResult runGenerate(Integer year, String triggerType, String fireInstanceId, String operator) {
        LocalDateTime startedAt = LocalDateTime.now();
        if (fireInstanceId == null || fireInstanceId.trim().isEmpty()) {
            fireInstanceId = "MANUAL-" + startedAt.getYear() + pad(startedAt.getMonthValue())
                    + pad(startedAt.getDayOfMonth()) + "-" + manualSeq.incrementAndGet();
        }
        RunResult result = new RunResult();
        AnnualReport report = null;
        String errorMessage = null;
        try {
            report = reportService.generateForYear(year, operator);
        } catch (Exception ex) {
            errorMessage = stackOf(ex);
            log.error("年度报表 Quartz 生成失败：year={}, trigger={}, fire={}",
                    year, triggerType, fireInstanceId, ex);
        }
        LocalDateTime finishedAt = LocalDateTime.now();

        AnnualReportJobLog jobLog = new AnnualReportJobLog();
        jobLog.setFireInstanceId(fireInstanceId);
        jobLog.setReportYear(year);
        jobLog.setTriggerType(triggerType);
        jobLog.setStartedAt(startedAt);
        jobLog.setFinishedAt(finishedAt);
        if (errorMessage == null) {
            jobLog.setStatus(JOB_SUCCESS);
            jobLog.setReportId(report.getId());
            jobLog.setReportNo(report.getReportNo());
            jobLog.setMessage("生成成功：" + report.getReportNo() + "（口径 V"
                    + report.getMetricVersionNo() + "）");
        } else {
            jobLog.setStatus(JOB_FAILED);
            jobLog.setMessage("年度报表生成失败，请查看告警与错误堆栈");
            jobLog.setErrorMessage(truncate(errorMessage, 4000));
        }
        jobLogMapper.insert(jobLog);
        result.jobLog = jobLog;

        if (errorMessage == null) {
            result.success = true;
            result.report = report;
            return result;
        }

        // 失败：同轮出一条 OPEN 告警（uk(job_log_id) 兜底，一条失败日志至多一条告警）
        AnnualReportAlert alert = new AnnualReportAlert();
        alert.setAlertNo(String.format("ARA-%06d", alertMapper.nextAlertSeq()));
        alert.setJobLogId(jobLog.getId());
        alert.setFireInstanceId(fireInstanceId);
        alert.setReportYear(year);
        alert.setAlertType(ALERT_GENERATE_FAILED);
        alert.setStatus(ALERT_OPEN);
        alert.setMessage(truncate(errorMessage, 4000));
        alert.setRaisedAt(finishedAt);
        alertMapper.insert(alert);

        result.success = false;
        result.errorMessage = errorMessage;
        result.alert = alert;
        return result;
    }

    public List<AnnualReportJobLog> listJobLogs(Integer year) {
        LambdaQueryWrapper<AnnualReportJobLog> w = new LambdaQueryWrapper<AnnualReportJobLog>()
                .orderByDesc(AnnualReportJobLog::getId);
        if (year != null) {
            w.eq(AnnualReportJobLog::getReportYear, year);
        }
        return jobLogMapper.selectList(w);
    }

    public List<AnnualReportAlert> listAlerts(String status) {
        LambdaQueryWrapper<AnnualReportAlert> w = new LambdaQueryWrapper<AnnualReportAlert>()
                .orderByDesc(AnnualReportAlert::getId);
        if (status != null && !status.trim().isEmpty()) {
            w.eq(AnnualReportAlert::getStatus, status);
        }
        return alertMapper.selectList(w);
    }

    /** 人工确认告警：只关闭告警，不改变任何报表状态。 */
    public AnnualReportAlert acknowledge(Long alertId, String by, String note) {
        AnnualReportAlert alert = alertMapper.selectById(alertId);
        if (alert == null) {
            throw new IllegalArgumentException("年度报表告警不存在: id=" + alertId);
        }
        if (ALERT_ACKED.equals(alert.getStatus())) {
            throw new IllegalArgumentException("告警已确认，不能重复确认: " + alert.getAlertNo());
        }
        LocalDateTime now = LocalDateTime.now();
        alert.setStatus(ALERT_ACKED);
        alert.setAcknowledgedBy(by);
        alert.setAcknowledgedAt(now);
        alert.setAckNote(note);
        alertMapper.updateById(alert);
        return alert;
    }

    private String pad(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    private String stackOf(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String msg = t.getClass().getName() + ": " + t.getMessage() + "\n" + sw;
        return truncate(msg, 4000);
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
