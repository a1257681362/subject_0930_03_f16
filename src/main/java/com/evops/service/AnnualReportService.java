package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.dto.MetricVersionCreateRequest;
import com.evops.entity.*;
import com.evops.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static com.evops.constant.MuseumConstants.*;

/**
 * 文物修复年度报表领域服务（museum-16）。
 *
 * <p>核心口径保障：</p>
 * <ul>
 *   <li>指标口径按版本保存：发布新版本旧版置 SUPERSEDED 且字段不改写；报表生成时
 *       冗余固化口径版本号与各口径，之后口径再变更，在途核对与归档后核对都按原版本。</li>
 *   <li>未归档可撤回后重提：重提另出新编号报表，编号/口径版本/生成时间各自可辨，
 *       被撤回报表与其凭证原样保留。</li>
 *   <li>归档冻结：归档后数字以 ARCHIVED 快照为准，任何重新核对只追加 RECHECK 快照；
 *       异常凭证只 INSERT，命中既有凭证（uk）跳过，绝不覆盖或删除已有凭证。</li>
 * </ul>
 */
@Service
public class AnnualReportService {

    private final AnnualMetricVersionMapper metricMapper;
    private final AnnualReportMapper reportMapper;
    private final AnnualReportSnapshotMapper snapshotMapper;
    private final AnnualReportAnomalyEvidenceMapper evidenceMapper;
    private final RepairProjectMapper projectMapper;
    private final RelicMapper relicMapper;
    private final AcceptReportMapper acceptReportMapper;
    private final MaterialUsageMapper usageMapper;
    private final MaterialUsageLineMapper usageLineMapper;
    private final MaterialReturnMapper materialReturnMapper;
    private final LoanAgreementMapper agreementMapper;
    private final LoanAgreementVersionMapper agreementVersionMapper;
    private final EscortNodeMapper escortNodeMapper;
    private final RepairEquipmentFaultMapper faultMapper;
    private final RepairTimesheetMapper timesheetMapper;
    private final RepairTimesheetLineMapper timesheetLineMapper;
    private final ObjectMapper objectMapper;

    public AnnualReportService(AnnualMetricVersionMapper metricMapper,
                               AnnualReportMapper reportMapper,
                               AnnualReportSnapshotMapper snapshotMapper,
                               AnnualReportAnomalyEvidenceMapper evidenceMapper,
                               RepairProjectMapper projectMapper,
                               RelicMapper relicMapper,
                               AcceptReportMapper acceptReportMapper,
                               MaterialUsageMapper usageMapper,
                               MaterialUsageLineMapper usageLineMapper,
                               MaterialReturnMapper materialReturnMapper,
                               LoanAgreementMapper agreementMapper,
                               LoanAgreementVersionMapper agreementVersionMapper,
                               EscortNodeMapper escortNodeMapper,
                               RepairEquipmentFaultMapper faultMapper,
                               RepairTimesheetMapper timesheetMapper,
                               RepairTimesheetLineMapper timesheetLineMapper,
                               ObjectMapper objectMapper) {
        this.metricMapper = metricMapper;
        this.reportMapper = reportMapper;
        this.snapshotMapper = snapshotMapper;
        this.evidenceMapper = evidenceMapper;
        this.projectMapper = projectMapper;
        this.relicMapper = relicMapper;
        this.acceptReportMapper = acceptReportMapper;
        this.usageMapper = usageMapper;
        this.usageLineMapper = usageLineMapper;
        this.materialReturnMapper = materialReturnMapper;
        this.agreementMapper = agreementMapper;
        this.agreementVersionMapper = agreementVersionMapper;
        this.escortNodeMapper = escortNodeMapper;
        this.faultMapper = faultMapper;
        this.timesheetMapper = timesheetMapper;
        this.timesheetLineMapper = timesheetLineMapper;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // 指标口径版本
    // ------------------------------------------------------------------

    /** 登记一个口径版本（默认 DRAFT）；publish=true 时直接发布生效。 */
    @Transactional
    public AnnualMetricVersion createMetricVersion(MetricVersionCreateRequest req) {
        int no = maxVersionNo() + 1;
        AnnualMetricVersion v = new AnnualMetricVersion();
        v.setMetricVersionNo(String.format("AMV-%06d", metricMapper.nextMetricSeq()));
        v.setVersionNo(no);
        v.setProjectInitBasis(orDefault(req.getProjectInitBasis(), "PROJECT_CREATE_TIME"));
        v.setProjectFinishBasis(orDefault(req.getProjectFinishBasis(), "PROJECT_FINISH_DATE"));
        v.setProjectFinishStatuses(orDefault(req.getProjectFinishStatuses(), "REPAIRED,ACCEPTED"));
        v.setMaterialConsumeBasis(orDefault(req.getMaterialConsumeBasis(), "ACTUAL_MINUS_RETURN"));
        v.setLoanBasis(orDefault(req.getLoanBasis(), "LOAN_AGREEMENT_SIGNED"));
        v.setAnomalySources(orDefault(req.getAnomalySources(), "EQUIPMENT_FAULT,ESCORT_ANOMALY"));
        v.setAcceptanceBasis(orDefault(req.getAcceptanceBasis(), "ACCEPT_REPORT_PASSED"));
        v.setStatus(METRIC_DRAFT);
        v.setChangeReason(req.getChangeReason());
        metricMapper.insert(v);
        if (Boolean.TRUE.equals(req.getPublish())) {
            doPublish(v, req.getOperator());
        }
        return v;
    }

    /** 发布口径版本：同事务旧 ACTIVE 置 SUPERSEDED（字段永不改写），本版置 ACTIVE。 */
    @Transactional
    public AnnualMetricVersion publishMetricVersion(Long versionId, String operator) {
        AnnualMetricVersion v = requireMetricVersion(versionId);
        return doPublish(v, operator);
    }

    private AnnualMetricVersion doPublish(AnnualMetricVersion v, String operator) {
        if (METRIC_ACTIVE.equals(v.getStatus())) {
            return v;
        }
        List<AnnualMetricVersion> actives = metricMapper.selectList(
                new LambdaQueryWrapper<AnnualMetricVersion>()
                        .eq(AnnualMetricVersion::getStatus, METRIC_ACTIVE));
        for (AnnualMetricVersion old : actives) {
            old.setStatus(METRIC_SUPERSEDED);
            metricMapper.updateById(old);
        }
        LocalDateTime now = LocalDateTime.now();
        v.setStatus(METRIC_ACTIVE);
        v.setPublishedBy(operator);
        v.setPublishedAt(now);
        metricMapper.updateById(v);
        return v;
    }

    public AnnualMetricVersion requireActiveMetricVersion() {
        AnnualMetricVersion active = metricMapper.selectOne(
                new LambdaQueryWrapper<AnnualMetricVersion>()
                        .eq(AnnualMetricVersion::getStatus, METRIC_ACTIVE)
                        .last("LIMIT 1"));
        if (active == null) {
            throw new IllegalArgumentException("尚无生效的指标口径版本，请先发布口径版本再生成年度报表");
        }
        return active;
    }

    public AnnualMetricVersion requireMetricVersion(Long id) {
        AnnualMetricVersion v = metricMapper.selectById(id);
        if (v == null) {
            throw new IllegalArgumentException("指标口径版本不存在: id=" + id);
        }
        return v;
    }

    public List<AnnualMetricVersion> listMetricVersions() {
        return metricMapper.selectList(new LambdaQueryWrapper<AnnualMetricVersion>()
                .orderByDesc(AnnualMetricVersion::getVersionNo));
    }

    private int maxVersionNo() {
        return metricMapper.selectList(new LambdaQueryWrapper<AnnualMetricVersion>()
                        .orderByDesc(AnnualMetricVersion::getVersionNo).last("LIMIT 1"))
                .stream().findFirst().map(AnnualMetricVersion::getVersionNo).orElse(0);
    }

    // ------------------------------------------------------------------
    // 年度报表生成 / 撤回 / 重提 / 归档 / 归档后核对
    // ------------------------------------------------------------------

    /**
     * 按年份与当前生效口径版本生成年度报表。
     * 已存在在途（GENERATED/ARCHIVED）报表时幂等返回既有报表且不重复创建。
     * 撤回重提另出新编号（{@code supersedesReportId} 指向被撤回报表）。
     */
    @Transactional
    public AnnualReport generateForYear(Integer year, String operator) {
        if (year == null) {
            throw new IllegalArgumentException("年度报表生成失败：报表年份不能为空");
        }
        AnnualReport active = findActiveReport(year);
        if (active != null) {
            return active;
        }
        AnnualMetricVersion metric = requireActiveMetricVersion();

        // 撤回重提：指向本年最近一份被撤回的报表
        AnnualReport withdrawn = reportMapper.selectList(new LambdaQueryWrapper<AnnualReport>()
                        .eq(AnnualReport::getReportYear, year)
                        .eq(AnnualReport::getStatus, REPORT_WITHDRAWN)
                        .orderByDesc(AnnualReport::getId).last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        boolean resubmit = withdrawn != null;

        LocalDateTime now = LocalDateTime.now();
        StatsBundle bundle = buildStats(metric, year, now);

        AnnualReport report = new AnnualReport();
        report.setReportNo(nextReportNo(year));
        report.setReportYear(year);
        report.setPeriodStart(LocalDate.of(year, 1, 1));
        report.setPeriodEnd(LocalDate.of(year, 12, 31));
        report.setMetricVersionId(metric.getId());
        report.setMetricVersionNo(metric.getVersionNo());
        report.setMetricVersionCode(metric.getMetricVersionNo());
        report.setStatus(REPORT_GENERATED);
        report.setActiveKey(activeKey(year));
        report.setSupersedesReportId(resubmit ? withdrawn.getId() : null);
        fillCounts(report, bundle);
        report.setGeneratedBy(operator);
        report.setGeneratedAt(now);
        reportMapper.insert(report);

        insertSnapshot(report, resubmit ? SNAPSHOT_RESUBMIT : SNAPSHOT_GENERATED,
                bundle, operator, now, resubmit
                        ? "撤回重提：替代已撤回报表 " + withdrawn.getReportNo()
                        + "，口径版本 V" + metric.getVersionNo() : null);
        insertAnomalyEvidence(report, bundle, now);
        return report;
    }

    /** 撤回未归档（GENERATED）报表；已归档为终态不可撤回。 */
    @Transactional
    public AnnualReport withdraw(Long reportId, String operator, String reason) {
        AnnualReport report = requireReport(reportId);
        if (!REPORT_GENERATED.equals(report.getStatus())) {
            throw new IllegalArgumentException("仅未归档（GENERATED）报表可撤回，当前状态 "
                    + report.getStatus() + "，归档后不可撤回: " + report.getReportNo());
        }
        LocalDateTime now = LocalDateTime.now();
        // 显式置空 active_key（MyBatis-Plus updateById 默认忽略 null），释放"同年在途唯一键"
        reportMapper.update(null, new LambdaUpdateWrapper<AnnualReport>()
                .eq(AnnualReport::getId, report.getId())
                .set(AnnualReport::getStatus, REPORT_WITHDRAWN)
                .set(AnnualReport::getActiveKey, null)
                .set(AnnualReport::getWithdrawnBy, operator)
                .set(AnnualReport::getWithdrawnAt, now)
                .set(AnnualReport::getWithdrawReason, reason));
        report.setStatus(REPORT_WITHDRAWN);
        report.setActiveKey(null);
        report.setWithdrawnBy(operator);
        report.setWithdrawnAt(now);
        report.setWithdrawReason(reason);
        // 快照与凭证原样保留，不做任何删除/改写
        return report;
    }

    /** 归档：终态冻结，出 ARCHIVED 快照；归档后只能追加 RECHECK 快照。 */
    @Transactional
    public AnnualReport archive(Long reportId, String operator) {
        AnnualReport report = requireReport(reportId);
        if (REPORT_ARCHIVED.equals(report.getStatus())) {
            throw new IllegalArgumentException("报表已归档，不能重复归档: " + report.getReportNo());
        }
        if (REPORT_WITHDRAWN.equals(report.getStatus())) {
            throw new IllegalArgumentException("报表已撤回，不能归档: " + report.getReportNo());
        }
        AnnualMetricVersion metric = requireMetricVersion(report.getMetricVersionId());
        LocalDateTime now = LocalDateTime.now();
        StatsBundle bundle = buildStats(metric, report.getReportYear(), now);

        report.setStatus(REPORT_ARCHIVED);
        report.setArchivedBy(operator);
        report.setArchivedAt(now);
        fillCounts(report, bundle);
        reportMapper.updateById(report);

        insertSnapshot(report, SNAPSHOT_ARCHIVED, bundle, operator, now, "归档冻结");
        insertAnomalyEvidence(report, bundle, now);
        return report;
    }

    /**
     * 归档后按原口径版本重新核对：只追加 RECHECK 快照与新增异常凭证，
     * 不更新报表数字、不覆盖归档快照与既有凭证。
     */
    @Transactional
    public AnnualReportSnapshot recheck(Long reportId, String operator) {
        AnnualReport report = requireReport(reportId);
        if (!REPORT_ARCHIVED.equals(report.getStatus())) {
            throw new IllegalArgumentException("只有已归档报表才能做归档后核对，当前状态 "
                    + report.getStatus() + ": " + report.getReportNo());
        }
        // 关键：始终取报表固化的原口径版本，即使期间口径发布了新版本
        AnnualMetricVersion metric = requireMetricVersion(report.getMetricVersionId());
        LocalDateTime now = LocalDateTime.now();
        StatsBundle bundle = buildStats(metric, report.getReportYear(), now);
        int before = countEvidence(report.getId());
        insertAnomalyEvidence(report, bundle, now);
        int added = countEvidence(report.getId()) - before;
        return insertSnapshot(report, SNAPSHOT_RECHECK, bundle, operator, now,
                "归档后按原口径版本 V" + metric.getVersionNo()
                        + " 重新核对，仅追加不改写；本次新增异常凭证 " + added + " 条");
    }

    // ------------------------------------------------------------------
    // 导出裁剪：按馆区与保密级别（ONE 最高 / THREE 最低）裁剪行集与字段
    // ------------------------------------------------------------------

    /**
     * 导出视图：行集限定为 viewerCampus（非空时）且密级不高于 clearanceGrade 可见范围的
     * 已完成项目；分组同步裁剪；字段（列）按密级逐级放开。
     *
     * <p>密级方向：ONE 最敏感。clearance=ONE 可见全部，TWO 可见 TWO/THREE，
     * THREE 仅见 THREE。缺省 clearance 按 THREE（最小可见面）处理。</p>
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> exportView(Long reportId, String viewerCampus, String clearanceGrade) {
        AnnualReport report = requireReport(reportId);
        AnnualReportSnapshot snapshot = latestSnapshot(reportId);
        Map<String, Object> stats = parseStats(snapshot == null ? null : snapshot.getStatsJson());
        int viewerRank = gradeRank(clearanceGrade == null ? "THREE" : clearanceGrade);

        List<Map<String, Object>> rows = new ArrayList<>();
        if (stats != null && stats.get("projectRows") instanceof List) {
            for (Object o : (List<Object>) stats.get("projectRows")) {
                Map<String, Object> row = (Map<String, Object>) o;
                String campus = (String) row.get("campusCode");
                String grade = (String) row.get("preservationGrade");
                if (viewerCampus != null && !viewerCampus.trim().isEmpty()
                        && !viewerCampus.trim().equals(campus)) {
                    continue;
                }
                if (gradeRank(grade == null ? "THREE" : grade) < viewerRank) {
                    continue;
                }
                rows.add(trimRow(row, viewerRank));
            }
        }

        List<Map<String, Object>> campusGroups = new ArrayList<>();
        if (stats != null && stats.get("campusGroups") instanceof List) {
            for (Object o : (List<Object>) stats.get("campusGroups")) {
                Map<String, Object> g = (Map<String, Object>) o;
                if (viewerCampus != null && !viewerCampus.trim().isEmpty()
                        && !viewerCampus.trim().equals(g.get("campusCode"))) {
                    continue;
                }
                campusGroups.add(g);
            }
        }
        List<Map<String, Object>> gradeGroups = new ArrayList<>();
        if (stats != null && stats.get("gradeGroups") instanceof List) {
            for (Object o : (List<Object>) stats.get("gradeGroups")) {
                Map<String, Object> g = (Map<String, Object>) o;
                String grade = (String) g.get("preservationGrade");
                if (gradeRank(grade == null ? "THREE" : grade) >= viewerRank) {
                    gradeGroups.add(g);
                }
            }
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("reportNo", report.getReportNo());
        view.put("reportYear", report.getReportYear());
        view.put("metricVersionNo", report.getMetricVersionNo());
        view.put("viewerCampus", viewerCampus);
        view.put("clearanceGrade", clearanceGrade == null ? "THREE" : clearanceGrade);
        view.put("columns", exportColumns(viewerRank));
        view.put("campusGroups", campusGroups);
        view.put("gradeGroups", gradeGroups);
        view.put("projectRows", rows);
        return view;
    }

    /** CSV 导出：与 exportView 同一裁剪行集、同一列口径；带 BOM。 */
    public String exportCsv(Long reportId, String viewerCampus, String clearanceGrade) {
        Map<String, Object> view = exportView(reportId, viewerCampus, clearanceGrade);
        int rank = gradeRank(clearanceGrade == null ? "THREE" : clearanceGrade);
        List<String> columns = exportColumns(rank);
        StringBuilder csv = new StringBuilder("﻿");
        List<String> headers = new ArrayList<>();
        for (String col : columns) {
            headers.add(columnLabel(col));
        }
        csv.append(String.join(",", headers)).append('\n');
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) view.get("projectRows");
        for (Map<String, Object> row : rows) {
            List<String> cells = new ArrayList<>();
            for (String col : columns) {
                cells.add(csvCell(valueOf(row, col)));
            }
            csv.append(String.join(",", cells)).append('\n');
        }
        return csv.toString();
    }

    private String columnLabel(String col) {
        switch (col) {
            case "projectNo": return "项目编号";
            case "campusCode": return "馆区";
            case "preservationGrade": return "保密级别";
            case "relicNo": return "文物编号";
            case "relicName": return "文物名称";
            case "client": return "委托人";
            case "finishDate": return "完成日期";
            case "acceptanceStatus": return "验收资料";
            default: return col;
        }
    }

    /** 按可见密级裁剪字段：THREE 仅基础列；TWO 增保密级别；ONE 再增委托人。 */
    private Map<String, Object> trimRow(Map<String, Object> row, int viewerRank) {
        Map<String, Object> trimmed = new LinkedHashMap<>();
        trimmed.put("projectNo", row.get("projectNo"));
        trimmed.put("campusCode", row.get("campusCode"));
        if (viewerRank <= 2) {
            trimmed.put("preservationGrade", row.get("preservationGrade"));
        }
        trimmed.put("relicNo", row.get("relicNo"));
        trimmed.put("relicName", row.get("relicName"));
        if (viewerRank == 1) {
            trimmed.put("client", row.get("client"));
        }
        trimmed.put("finishDate", row.get("finishDate"));
        trimmed.put("acceptanceStatus", row.get("acceptanceStatus"));
        return trimmed;
    }

    private List<String> exportColumns(int viewerRank) {
        List<String> columns = new ArrayList<>(Arrays.asList(
                "projectNo", "campusCode"));
        if (viewerRank <= 2) {
            columns.add("preservationGrade");
        }
        columns.addAll(Arrays.asList("relicNo", "relicName"));
        if (viewerRank == 1) {
            columns.add("client");
        }
        columns.addAll(Arrays.asList("finishDate", "acceptanceStatus"));
        return columns;
    }

    /** ONE=1 最敏感，TWO=2，THREE=3（默认最低）。 */
    private int gradeRank(String grade) {
        if ("ONE".equals(grade)) {
            return 1;
        }
        if ("TWO".equals(grade)) {
            return 2;
        }
        return 3;
    }

    private String valueOf(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? "" : v.toString();
    }

    private String csvCell(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public AnnualReport requireReport(Long id) {
        AnnualReport report = reportMapper.selectById(id);
        if (report == null) {
            throw new IllegalArgumentException("年度报表不存在: id=" + id);
        }
        return report;
    }

    public AnnualReport findActiveReport(Integer year) {
        return reportMapper.selectOne(new LambdaQueryWrapper<AnnualReport>()
                .eq(AnnualReport::getActiveKey, activeKey(year)).last("LIMIT 1"));
    }

    public List<AnnualReport> listByYear(Integer year) {
        LambdaQueryWrapper<AnnualReport> w = new LambdaQueryWrapper<AnnualReport>()
                .orderByDesc(AnnualReport::getId);
        if (year != null) {
            w.eq(AnnualReport::getReportYear, year);
        }
        return reportMapper.selectList(w);
    }

    public List<AnnualReportSnapshot> listSnapshots(Long reportId) {
        return snapshotMapper.selectList(new LambdaQueryWrapper<AnnualReportSnapshot>()
                .eq(AnnualReportSnapshot::getReportId, reportId)
                .orderByAsc(AnnualReportSnapshot::getSeqNo));
    }

    public AnnualReportSnapshot latestSnapshot(Long reportId) {
        return snapshotMapper.selectList(new LambdaQueryWrapper<AnnualReportSnapshot>()
                        .eq(AnnualReportSnapshot::getReportId, reportId)
                        .orderByDesc(AnnualReportSnapshot::getSeqNo).last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    public List<AnnualReportAnomalyEvidence> listEvidence(Long reportId) {
        return evidenceMapper.selectList(new LambdaQueryWrapper<AnnualReportAnomalyEvidence>()
                .eq(AnnualReportAnomalyEvidence::getReportId, reportId)
                .orderByAsc(AnnualReportAnomalyEvidence::getId));
    }

    /** 报表详情：主单 + 快照序列 + 凭证（含统计 JSON 解析后的行集）。 */
    public Map<String, Object> detail(Long reportId) {
        AnnualReport report = requireReport(reportId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("report", report);
        List<Map<String, Object>> snapshots = new ArrayList<>();
        for (AnnualReportSnapshot s : listSnapshots(reportId)) {
            Map<String, Object> bundle = new LinkedHashMap<>();
            bundle.put("snapshot", s);
            bundle.put("stats", parseStats(s.getStatsJson()));
            snapshots.add(bundle);
        }
        detail.put("snapshots", snapshots);
        detail.put("evidence", listEvidence(reportId));
        return detail;
    }

    // ------------------------------------------------------------------
    // 统计取数（按固化口径版本 + 年度时间窗）
    // ------------------------------------------------------------------

    /** 统计结果包：汇总 + 馆区/密级分组 + 项目明细 + 异常凭证草稿。 */
    public static class StatsBundle {
        public int initCount;
        public int finishCount;
        public int pendingAcceptCount;
        public int loanCount;
        public int anomalyCount;
        public BigDecimal materialQty = BigDecimal.ZERO;
        public List<Map<String, Object>> projectRows = new ArrayList<>();
        public List<Map<String, Object>> campusGroups = new ArrayList<>();
        public List<Map<String, Object>> gradeGroups = new ArrayList<>();
        public List<AnnualReportAnomalyEvidence> anomalies = new ArrayList<>();
    }

    private StatsBundle buildStats(AnnualMetricVersion metric, int year, LocalDateTime now) {
        LocalDateTime yearStart = LocalDate.of(year, 1, 1).atStartOfDay();
        LocalDateTime nextStart = LocalDate.of(year + 1, 1, 1).atStartOfDay();
        Set<String> finishStatuses = new LinkedHashSet<>(Arrays.asList(
                metric.getProjectFinishStatuses().split("\\s*,\\s*")));
        Set<String> anomalySources = new LinkedHashSet<>(Arrays.asList(
                metric.getAnomalySources().split("\\s*,\\s*")));

        StatsBundle b = new StatsBundle();
        Map<Long, Relic> relicCache = new HashMap<>();

        // 立项：按立项创建时间落年度（含所有未作废口径这里取全部状态；作废项目另行标注）
        List<RepairProject> initProjects = projectMapper.selectList(
                new LambdaQueryWrapper<RepairProject>()
                        .ge(RepairProject::getCreateTime, yearStart)
                        .lt(RepairProject::getCreateTime, nextStart));
        b.initCount = initProjects.size();

        // 完成：按修复完成日落年度且状态在口径内
        List<RepairProject> finishedProjects = projectMapper.selectList(
                new LambdaQueryWrapper<RepairProject>()
                        .isNotNull(RepairProject::getFinishDate)
                        .ge(RepairProject::getFinishDate, yearStart.toLocalDate())
                        .lt(RepairProject::getFinishDate, nextStart.toLocalDate())
                        .in(RepairProject::getStatus, finishStatuses));
        b.finishCount = finishedProjects.size();

        for (RepairProject p : finishedProjects) {
            Relic relic = relicCache.computeIfAbsent(p.getRelicId(), relicMapper::selectById);
            boolean passed = acceptReportMapper.selectCount(new LambdaQueryWrapper<AcceptReport>()
                    .eq(AcceptReport::getProjectId, p.getId())
                    .eq(AcceptReport::getStatus, "PASSED")) > 0;
            String acceptanceStatus = passed ? "OK" : "PENDING";
            if (!passed) {
                b.pendingAcceptCount++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("projectId", p.getId());
            row.put("projectNo", p.getProjectNo());
            row.put("relicId", p.getRelicId());
            row.put("relicNo", relic == null ? null : relic.getRelicNo());
            row.put("relicName", relic == null ? null : relic.getName());
            row.put("campusCode", relic == null ? null : relic.getCampusCode());
            row.put("preservationGrade", relic == null ? null : relic.getPreservationGrade());
            row.put("client", p.getClient());
            row.put("finishDate", p.getFinishDate());
            row.put("acceptanceStatus", acceptanceStatus);
            b.projectRows.add(row);
        }

        // 材料消耗：本年实际领用（按领用日落年）- 本年退料（按退料日落年），按馆区分组
        List<MaterialUsage> usages = usageMapper.selectList(new LambdaQueryWrapper<MaterialUsage>()
                .ge(MaterialUsage::getUsageAt, yearStart)
                .lt(MaterialUsage::getUsageAt, nextStart));
        Map<Long, BigDecimal> issuedByRelic = new HashMap<>();
        BigDecimal issuedTotal = BigDecimal.ZERO;
        for (MaterialUsage u : usages) {
            BigDecimal sum = usageLineMapper.selectList(
                            new LambdaQueryWrapper<MaterialUsageLine>()
                                    .eq(MaterialUsageLine::getUsageId, u.getId()))
                    .stream()
                    .map(MaterialUsageLine::getActualQty).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            issuedByRelic.merge(u.getRelicId(), sum, BigDecimal::add);
            issuedTotal = issuedTotal.add(sum);
        }
        List<MaterialReturn> returns = materialReturnMapper.selectList(
                new LambdaQueryWrapper<MaterialReturn>()
                        .ge(MaterialReturn::getReturnedAt, yearStart)
                        .lt(MaterialReturn::getReturnedAt, nextStart));
        Map<Long, BigDecimal> returnedByRelic = new HashMap<>();
        BigDecimal returnedTotal = BigDecimal.ZERO;
        for (MaterialReturn r : returns) {
            returnedByRelic.merge(r.getRelicId(),
                    r.getReturnQty() == null ? BigDecimal.ZERO : r.getReturnQty(), BigDecimal::add);
            returnedTotal = returnedTotal.add(r.getReturnQty() == null ? BigDecimal.ZERO : r.getReturnQty());
        }
        b.materialQty = issuedTotal.subtract(returnedTotal);

        // 借展次数：本年借展协议签署版本数（每次签署计一次，变更重签另计一次）
        List<LoanAgreementVersion> signedVersions = agreementVersionMapper.selectList(
                new LambdaQueryWrapper<LoanAgreementVersion>()
                        .eq(LoanAgreementVersion::getStatus, "SIGNED")
                        .ge(LoanAgreementVersion::getSignedAt, yearStart)
                        .lt(LoanAgreementVersion::getSignedAt, nextStart));
        b.loanCount = signedVersions.size();

        // 馆区/密级分组（立项、完成、待补、借展、异常、材料按馆区归集）
        Map<String, long[]> campusCounters = new TreeMap<>();
        Map<String, long[]> gradeCounters = new TreeMap<>();
        // 计数槽：0立项 1完成 2待补 3借展 4异常
        for (RepairProject p : initProjects) {
            Relic relic = relicCache.computeIfAbsent(p.getRelicId(), relicMapper::selectById);
            bump(campusCounters, relic == null ? null : relic.getCampusCode(), 0);
            bumpGrade(gradeCounters, relic == null ? null : relic.getPreservationGrade(), 0);
        }
        for (Map<String, Object> row : b.projectRows) {
            String campus = (String) row.get("campusCode");
            String grade = (String) row.get("preservationGrade");
            bump(campusCounters, campus, 1);
            bumpGrade(gradeCounters, grade, 1);
            if ("PENDING".equals(row.get("acceptanceStatus"))) {
                bump(campusCounters, campus, 2);
                bumpGrade(gradeCounters, grade, 2);
            }
        }
        for (LoanAgreementVersion sv : signedVersions) {
            LoanAgreement ag = agreementMapper.selectById(sv.getAgreementId());
            Relic relic = ag == null ? null : relicCache.computeIfAbsent(ag.getRelicId(), relicMapper::selectById);
            bump(campusCounters, relic == null ? null : relic.getCampusCode(), 3);
            bumpGrade(gradeCounters, relic == null ? null : relic.getPreservationGrade(), 3);
        }
        Map<String, BigDecimal> materialByCampus = new TreeMap<>();
        issuedByRelic.forEach((relicId, q) -> {
            Relic r = relicCache.computeIfAbsent(relicId, relicMapper::selectById);
            String campus = r == null || r.getCampusCode() == null ? "UNKNOWN" : r.getCampusCode();
            materialByCampus.merge(campus, q, BigDecimal::add);
        });
        returnedByRelic.forEach((relicId, q) -> {
            Relic r = relicCache.computeIfAbsent(relicId, relicMapper::selectById);
            String campus = r == null || r.getCampusCode() == null ? "UNKNOWN" : r.getCampusCode();
            materialByCampus.merge(campus, q.negate(), BigDecimal::add);
        });

        // 异常事件凭证（按口径来源开关）
        if (anomalySources.contains(ANOMALY_EQUIPMENT_FAULT)) {
            List<RepairEquipmentFault> faults = faultMapper.selectList(
                    new LambdaQueryWrapper<RepairEquipmentFault>()
                            .ge(RepairEquipmentFault::getFaultAt, yearStart)
                            .lt(RepairEquipmentFault::getFaultAt, nextStart)
                            .orderByAsc(RepairEquipmentFault::getFaultAt));
            for (RepairEquipmentFault f : faults) {
                Long[] pr = resolveEquipmentProject(f, now);
                Long projectId = pr[0];
                Long relicId = pr[1];
                Relic relic = relicId == null ? null
                        : relicCache.computeIfAbsent(relicId, relicMapper::selectById);
                AnnualReportAnomalyEvidence e = new AnnualReportAnomalyEvidence();
                e.setSource(ANOMALY_EQUIPMENT_FAULT);
                e.setSourceNo(f.getFaultNo());
                e.setProjectId(projectId);
                e.setRelicId(relicId);
                e.setCampusCode(relic == null ? null : relic.getCampusCode());
                e.setAnomalyAt(f.getFaultAt());
                e.setSummary("修复设备故障 " + f.getFaultNo() + "：" + f.getDescription());
                e.setRecordedAt(now);
                b.anomalies.add(e);
                bump(campusCounters, relic == null ? null : relic.getCampusCode(), 4);
                bumpGrade(gradeCounters, relic == null ? null : relic.getPreservationGrade(), 4);
            }
        }
        if (anomalySources.contains(ANOMALY_ESCORT)) {
            List<EscortNode> nodes = escortNodeMapper.selectList(new LambdaQueryWrapper<EscortNode>()
                    .eq(EscortNode::getAbnormal, 1)
                    .ge(EscortNode::getRecordedAt, yearStart)
                    .lt(EscortNode::getRecordedAt, nextStart)
                    .orderByAsc(EscortNode::getRecordedAt));
            for (EscortNode n : nodes) {
                LoanAgreement ag = agreementMapper.selectById(n.getAgreementId());
                Long relicId = ag == null ? null : ag.getRelicId();
                Relic relic = relicId == null ? null
                        : relicCache.computeIfAbsent(relicId, relicMapper::selectById);
                AnnualReportAnomalyEvidence e = new AnnualReportAnomalyEvidence();
                e.setSource(ANOMALY_ESCORT);
                e.setSourceNo((ag == null ? "AGR#" + n.getAgreementId() : ag.getAgreementNo())
                        + "#" + n.getNodeType());
                e.setRelicId(relicId);
                e.setCampusCode(relic == null ? null : relic.getCampusCode());
                e.setAnomalyAt(n.getRecordedAt());
                e.setSummary("借展押运节点异常 " + n.getNodeType() + "：" + n.getAnomalyReason());
                e.setRecordedAt(now);
                b.anomalies.add(e);
                bump(campusCounters, relic == null ? null : relic.getCampusCode(), 4);
                bumpGrade(gradeCounters, relic == null ? null : relic.getPreservationGrade(), 4);
            }
        }
        b.anomalyCount = b.anomalies.size();

        campusCounters.forEach((campus, c) -> {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("campusCode", campus);
            g.put("initCount", c[0]);
            g.put("finishCount", c[1]);
            g.put("pendingAcceptCount", c[2]);
            g.put("loanCount", c[3]);
            g.put("anomalyCount", c[4]);
            g.put("materialQty", materialByCampus.getOrDefault(campus, BigDecimal.ZERO));
            b.campusGroups.add(g);
        });
        gradeCounters.forEach((grade, c) -> {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("preservationGrade", grade);
            g.put("initCount", c[0]);
            g.put("finishCount", c[1]);
            g.put("pendingAcceptCount", c[2]);
            g.put("loanCount", c[3]);
            g.put("anomalyCount", c[4]);
            b.gradeGroups.add(g);
        });
        return b;
    }

    /** 由故障设备经工时明细行定位当时所在项目/文物；无法定位返回 [null,null]。 */
    private Long[] resolveEquipmentProject(RepairEquipmentFault f, LocalDateTime now) {
        List<RepairTimesheetLine> lines = timesheetLineMapper.selectList(
                new LambdaQueryWrapper<RepairTimesheetLine>()
                        .eq(RepairTimesheetLine::getEquipmentId, f.getEquipmentId())
                        .le(RepairTimesheetLine::getStartTime, f.getFaultAt())
                        .ge(RepairTimesheetLine::getEndTime, f.getFaultAt())
                        .last("LIMIT 1"));
        if (lines.isEmpty()) {
            lines = timesheetLineMapper.selectList(new LambdaQueryWrapper<RepairTimesheetLine>()
                    .eq(RepairTimesheetLine::getEquipmentId, f.getEquipmentId())
                    .orderByDesc(RepairTimesheetLine::getStartTime).last("LIMIT 1"));
        }
        if (lines.isEmpty()) {
            return new Long[]{null, null};
        }
        RepairTimesheet sheet = timesheetMapper.selectById(lines.get(0).getTimesheetId());
        if (sheet == null) {
            return new Long[]{null, null};
        }
        RepairProject project = projectMapper.selectById(sheet.getProjectId());
        return new Long[]{project.getId(), project.getRelicId()};
    }

    private void bump(Map<String, long[]> counters, String key, int slot) {
        counters.computeIfAbsent(key == null ? "UNKNOWN" : key, k -> new long[5])[slot]++;
    }

    private void bumpGrade(Map<String, long[]> counters, String key, int slot) {
        counters.computeIfAbsent(key == null ? "UNKNOWN" : key, k -> new long[5])[slot]++;
    }

    // ------------------------------------------------------------------
    // 快照 / 凭证写入
    // ------------------------------------------------------------------

    private AnnualReportSnapshot insertSnapshot(AnnualReport report, String type, StatsBundle bundle,
                                                String operator, LocalDateTime now, String note) {
        int seq = snapshotMapper.selectList(new LambdaQueryWrapper<AnnualReportSnapshot>()
                        .eq(AnnualReportSnapshot::getReportId, report.getId())
                        .orderByDesc(AnnualReportSnapshot::getSeqNo).last("LIMIT 1"))
                .stream().findFirst().map(AnnualReportSnapshot::getSeqNo).orElse(0) + 1;
        AnnualReportSnapshot s = new AnnualReportSnapshot();
        s.setSnapshotNo(String.format("ARS-%06d", snapshotMapper.nextSnapshotSeq()));
        s.setReportId(report.getId());
        s.setReportNo(report.getReportNo());
        s.setReportYear(report.getReportYear());
        s.setSeqNo(seq);
        s.setSnapshotType(type);
        s.setMetricVersionId(report.getMetricVersionId());
        s.setMetricVersionNo(report.getMetricVersionNo());
        s.setMetricVersionCode(report.getMetricVersionCode());
        s.setInitCount(bundle.initCount);
        s.setFinishCount(bundle.finishCount);
        s.setPendingAcceptCount(bundle.pendingAcceptCount);
        s.setLoanCount(bundle.loanCount);
        s.setAnomalyCount(bundle.anomalyCount);
        s.setMaterialQty(bundle.materialQty);
        s.setStatsJson(toStatsJson(report, bundle));
        s.setTriggeredBy(operator);
        s.setTriggeredAt(now);
        s.setNote(note);
        snapshotMapper.insert(s);
        return s;
    }

    /**
     * 只追加异常凭证：同一报表同一来源凭证命中唯一约束即跳过，绝不覆盖/更新已有凭证。
     */
    private void insertAnomalyEvidence(AnnualReport report, StatsBundle bundle, LocalDateTime now) {
        int seq = evidenceMapper.selectCount(new LambdaQueryWrapper<AnnualReportAnomalyEvidence>()
                .eq(AnnualReportAnomalyEvidence::getReportId, report.getId())) == null ? 0
                : evidenceMapper.selectCount(new LambdaQueryWrapper<AnnualReportAnomalyEvidence>()
                .eq(AnnualReportAnomalyEvidence::getReportId, report.getId())).intValue();
        for (AnnualReportAnomalyEvidence e : bundle.anomalies) {
            e.setReportId(report.getId());
            e.setReportYear(report.getReportYear());
            e.setSeqNo(++seq);
            e.setRecordedAt(now);
            try {
                evidenceMapper.insert(e);
            } catch (DuplicateKeyException dup) {
                // 凭证已存在（归档后核对补录）：只跳过，保留原凭证不覆盖
                seq--;
            }
        }
    }

    private int countEvidence(Long reportId) {
        Long c = evidenceMapper.selectCount(new LambdaQueryWrapper<AnnualReportAnomalyEvidence>()
                .eq(AnnualReportAnomalyEvidence::getReportId, reportId));
        return c == null ? 0 : c.intValue();
    }

    private String toStatsJson(AnnualReport report, StatsBundle b) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("reportNo", report.getReportNo());
        root.put("reportYear", report.getReportYear());
        root.put("periodStart", report.getPeriodStart().toString());
        root.put("periodEnd", report.getPeriodEnd().toString());
        root.put("metricVersionNo", report.getMetricVersionNo());
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("initCount", b.initCount);
        totals.put("finishCount", b.finishCount);
        totals.put("pendingAcceptCount", b.pendingAcceptCount);
        totals.put("loanCount", b.loanCount);
        totals.put("anomalyCount", b.anomalyCount);
        totals.put("materialQty", b.materialQty);
        root.put("totals", totals);
        root.put("campusGroups", b.campusGroups);
        root.put("gradeGroups", b.gradeGroups);
        root.put("projectRows", b.projectRows);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            throw new IllegalStateException("年度报表统计结果序列化失败: " + report.getReportNo(), ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseStats(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private void fillCounts(AnnualReport report, StatsBundle b) {
        report.setInitCount(b.initCount);
        report.setFinishCount(b.finishCount);
        report.setPendingAcceptCount(b.pendingAcceptCount);
        report.setLoanCount(b.loanCount);
        report.setAnomalyCount(b.anomalyCount);
        report.setMaterialQty(b.materialQty);
    }

    private String nextReportNo(int year) {
        return String.format("ARR-%d-%04d", year, reportMapper.nextReportSeq());
    }

    private String activeKey(int year) {
        return "Y" + year;
    }

    private String orDefault(String value, String def) {
        return value == null || value.trim().isEmpty() ? def : value.trim();
    }
}
