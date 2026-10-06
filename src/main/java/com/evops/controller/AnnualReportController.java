package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.*;
import com.evops.entity.AnnualMetricVersion;
import com.evops.entity.AnnualReport;
import com.evops.entity.AnnualReportAlert;
import com.evops.entity.AnnualReportJobLog;
import com.evops.entity.AnnualReportSnapshot;
import com.evops.service.AnnualReportJobService;
import com.evops.service.AnnualReportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文物修复年度报表接口（museum-16）。
 */
@RestController
@RequestMapping("/api/annual-reports")
public class AnnualReportController {

    private final AnnualReportService reportService;
    private final AnnualReportJobService jobService;
    private final org.quartz.Scheduler quartzScheduler;

    public AnnualReportController(AnnualReportService reportService,
                                  AnnualReportJobService jobService,
                                  org.quartz.Scheduler quartzScheduler) {
        this.reportService = reportService;
        this.jobService = jobService;
        this.quartzScheduler = quartzScheduler;
    }

    // ---- 指标口径版本 ----

    @PostMapping("/metric-versions")
    public ApiResponse<AnnualMetricVersion> createMetricVersion(
            @Valid @RequestBody MetricVersionCreateRequest req) {
        return ApiResponse.ok(reportService.createMetricVersion(req));
    }

    @PostMapping("/metric-versions/{id}/publish")
    public ApiResponse<AnnualMetricVersion> publishMetricVersion(@PathVariable Long id,
                                                                 @RequestParam String operator) {
        return ApiResponse.ok(reportService.publishMetricVersion(id, operator));
    }

    @GetMapping("/metric-versions")
    public ApiResponse<List<AnnualMetricVersion>> listMetricVersions() {
        return ApiResponse.ok(reportService.listMetricVersions());
    }

    // ---- 年度报表生命周期 ----

    /** 手动生成（记 MANUAL 任务日志；失败同样出告警）。 */
    @PostMapping("/generate")
    public ApiResponse<Map<String, Object>> generate(@Valid @RequestBody AnnualReportGenerateRequest req) {
        AnnualReportJobService.RunResult r = jobService.runGenerate(
                req.getYear(), "MANUAL", null, req.getOperator());
        return ApiResponse.ok(toResultMap(r));
    }

    /** 立即触发一次 Quartz Job（走 SCHEDULED 同一任务，fire 实例 id 由 Quartz 分配）。 */
    @PostMapping("/trigger-quartz")
    public ApiResponse<Map<String, Object>> triggerQuartz() throws Exception {
        quartzScheduler.triggerJob(
                org.quartz.JobKey.jobKey(
                        com.evops.config.AnnualReportQuartzConfig.JOB_NAME,
                        "MUSEUM_ANNUAL_REPORT"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("triggered", true);
        data.put("reportYear", LocalDate.now().getYear() - 1);
        data.put("note", "Quartz 任务已触发，结果见任务日志与告警");
        return ApiResponse.ok(data);
    }

    @PostMapping("/{id}/withdraw")
    public ApiResponse<AnnualReport> withdraw(@PathVariable Long id,
                                              @Valid @RequestBody AnnualReportWithdrawRequest req) {
        return ApiResponse.ok(reportService.withdraw(id, req.getOperator(), req.getReason()));
    }

    @PostMapping("/{id}/archive")
    public ApiResponse<AnnualReport> archive(@PathVariable Long id,
                                             @Valid @RequestBody AnnualReportArchiveRequest req) {
        return ApiResponse.ok(reportService.archive(id, req.getOperator()));
    }

    /** 归档后按原口径版本重新核对（只追加 RECHECK 快照与新凭证）。 */
    @PostMapping("/{id}/recheck")
    public ApiResponse<AnnualReportSnapshot> recheck(@PathVariable Long id,
                                                     @RequestParam String operator) {
        return ApiResponse.ok(reportService.recheck(id, operator));
    }

    @GetMapping
    public ApiResponse<List<AnnualReport>> list(@RequestParam(required = false) Integer year) {
        return ApiResponse.ok(reportService.listByYear(year));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(reportService.detail(id));
    }

    @GetMapping("/{id}/snapshots")
    public ApiResponse<List<AnnualReportSnapshot>> snapshots(@PathVariable Long id) {
        return ApiResponse.ok(reportService.listSnapshots(id));
    }

    // ---- 导出（按馆区 + 保密级别裁剪行与字段） ----

    @GetMapping("/{id}/export-view")
    public ApiResponse<Map<String, Object>> exportView(
            @PathVariable Long id,
            @RequestParam(required = false) String viewerCampus,
            @RequestParam(required = false, defaultValue = "THREE") String clearanceGrade) {
        return ApiResponse.ok(reportService.exportView(id, viewerCampus, clearanceGrade));
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<String> exportCsv(
            @PathVariable Long id,
            @RequestParam(required = false) String viewerCampus,
            @RequestParam(required = false, defaultValue = "THREE") String clearanceGrade) {
        String csv = reportService.exportCsv(id, viewerCampus, clearanceGrade);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDispositionFormData("attachment", "annual-report-" + id + ".csv");
        return new ResponseEntity<>(csv, headers, HttpStatus.OK);
    }

    // ---- 任务日志与告警 ----

    @GetMapping("/job-logs")
    public ApiResponse<List<AnnualReportJobLog>> jobLogs(@RequestParam(required = false) Integer year) {
        return ApiResponse.ok(jobService.listJobLogs(year));
    }

    @GetMapping("/alerts")
    public ApiResponse<List<AnnualReportAlert>> alerts(
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(jobService.listAlerts(status));
    }

    @PostMapping("/alerts/{id}/ack")
    public ApiResponse<AnnualReportAlert> ackAlert(@PathVariable Long id,
                                                   @Valid @RequestBody AnnualReportAlertAckRequest req) {
        return ApiResponse.ok(jobService.acknowledge(id, req.getAcknowledgedBy(), req.getAckNote()));
    }

    private Map<String, Object> toResultMap(AnnualReportJobService.RunResult r) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("success", r.success);
        data.put("report", r.report);
        data.put("jobLog", r.jobLog);
        data.put("alert", r.alert);
        return data;
    }
}
