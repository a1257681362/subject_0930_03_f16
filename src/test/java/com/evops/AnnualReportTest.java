package com.evops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.evops.entity.*;
import com.evops.mapper.*;
import com.evops.service.AnnualReportJobService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文物修复年度报表（museum-16）验收：
 * 立项/完成/材料消耗/借展次数/异常事件统计；缺验收资料项目标待补；
 * 指标口径按版本保存，年度报表先于口径变更时仍按原版本完成核对；
 * 未归档可撤回后重提（编号/口径版本/时间可辨），归档后只能追加新快照；
 * 异常凭证只追加不覆盖；Quartz 生成失败告警并保留任务日志；
 * 导出按馆区与保密级别裁剪行与字段。
 * 独立内存库；源数据用 Mapper 显式落历史时间。主统计年取系统上一年度（Y），
 * 与 Quartz 任务"生成上一完整自然年度"口径对齐。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-annual-report;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnnualReportTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Autowired private AnnualReportJobService jobService;
    @Autowired private AnnualReportJobLogMapper jobLogMapper;
    @Autowired private RelicMapper relicMapper;
    @Autowired private RepairProjectMapper projectMapper;
    @Autowired private AcceptReportMapper acceptReportMapper;
    @Autowired private MaterialUsageMapper usageMapper;
    @Autowired private MaterialUsageLineMapper usageLineMapper;
    @Autowired private MaterialReturnMapper returnMapper;
    @Autowired private LoanAgreementMapper agreementMapper;
    @Autowired private LoanAgreementVersionMapper agreementVersionMapper;
    @Autowired private EscortNodeMapper escortNodeMapper;
    @Autowired private RepairEquipmentMapper equipmentMapper;
    @Autowired private RepairEquipmentFaultMapper faultMapper;
    @Autowired private RepairTimesheetMapper timesheetMapper;
    @Autowired private RepairTimesheetLineMapper timesheetLineMapper;
    @Autowired private org.quartz.Scheduler quartzScheduler;

    /** 主统计年 = 系统上一年度，和 Quartz 年度任务口径一致 */
    private static final int Y = LocalDate.now().getYear() - 1;
    private static final LocalDateTime TY = LocalDateTime.of(Y, 6, 1, 10, 0);
    private static final LocalDateTime TY1 = LocalDateTime.of(Y + 1, 6, 1, 10, 0);
    private static final LocalDateTime TY2 = LocalDateTime.of(Y + 2, 3, 1, 10, 0);

    @Test
    void annual_report_full_lifecycle() throws Exception {
        seedSourceData();

        // ---- 发布口径版本 V1（默认口径：异常含设备故障+押运异常）----
        long v1 = dataOf(postOk("/api/annual-reports/metric-versions",
                "{\"operator\":\"统计员甲\",\"publish\":true,\"changeReason\":\"首版口径\"}"))
                .get("id").asLong();

        // ---- 生成上一年度（Y）报表，按 V1 ----
        JsonNode ry = generate(Y, "统计员甲");
        long reportId = ry.get("id").asLong();
        String noY = ry.get("reportNo").asText();
        assertTrue(noY.startsWith("ARR-" + Y + "-"), "年度报表编号应带年度: " + noY);
        assertEquals(1, ry.get("metricVersionNo").asInt());
        assertEquals("GENERATED", ry.get("status").asText());
        assertEquals(4, ry.get("initCount").asInt(), "立项数");
        assertEquals(4, ry.get("finishCount").asInt(), "完成数");
        assertEquals(1, ry.get("pendingAcceptCount").asInt(), "缺验收资料待补项目数");
        assertEquals(2, ry.get("loanCount").asInt(), "借展次数（上一年签署两次，更早一次不计）");
        assertEquals(2, ry.get("anomalyCount").asInt(), "异常事件=设备故障1+押运异常1");
        assertEquals(0, new BigDecimal(ry.get("materialQty").asText()).compareTo(new BigDecimal("70.000")),
                "材料消耗=领用100-退料30");

        // 待补标识落到项目行
        JsonNode detail = dataOf(getJson("/api/annual-reports/" + reportId));
        JsonNode snapshots = detail.get("snapshots");
        assertEquals(1, snapshots.size());
        assertEquals("GENERATED", snapshots.get(0).get("snapshot").get("snapshotType").asText());
        JsonNode rows = snapshots.get(0).get("stats").get("projectRows");
        int pendingRows = 0;
        for (JsonNode row : rows) {
            if ("PENDING".equals(row.get("acceptanceStatus").asText())) {
                pendingRows++;
                assertEquals("MP-002", row.get("projectNo").asText(), "无 PASSED 成果报告的项目标待补");
            }
        }
        assertEquals(1, pendingRows);

        // 幂等：在途时重复生成返回同一份，不另建
        assertEquals(reportId, generate(Y, "统计员甲").get("id").asLong(),
                "同年在途报表重复生成须幂等返回同一份");

        // ---- Y+2 首版（V1），撤回，发布 V2，再重提 → 编号/口径/时间可辨 ----
        JsonNode rFutureFirst = generate(Y + 2, "统计员甲");
        long firstId = rFutureFirst.get("id").asLong();
        String firstNo = rFutureFirst.get("reportNo").asText();
        assertEquals(1, rFutureFirst.get("metricVersionNo").asInt());
        LocalDateTime firstGeneratedAt = LocalDateTime.parse(rFutureFirst.get("generatedAt").asText());

        postOk("/api/annual-reports/" + firstId + "/withdraw",
                "{\"operator\":\"统计员甲\",\"reason\":\"数据需修订后重提\"}");
        JsonNode withdrawn = dataOf(getJson("/api/annual-reports/" + firstId));
        assertEquals("WITHDRAWN", withdrawn.get("report").get("status").asText());
        assertTrue(withdrawn.get("report").get("activeKey").isNull(), "撤回后在途唯一键须释放");
        assertEquals(1, withdrawn.get("snapshots").size(), "撤回报表的快照必须保留");

        // 发布 V2：异常只统计设备故障（排除押运异常）；旧版置 SUPERSEDED
        long v2 = dataOf(postOk("/api/annual-reports/metric-versions",
                "{\"operator\":\"统计处长\",\"publish\":true,"
                        + "\"anomalySources\":\"EQUIPMENT_FAULT\","
                        + "\"changeReason\":\"异常口径收紧，仅计设备故障\"}")).get("id").asLong();
        assertNotEquals(v1, v2);
        JsonNode versions = dataOf(getJson("/api/annual-reports/metric-versions"));
        assertEquals("ACTIVE", versions.get(0).get("status").asText());
        assertEquals(2, versions.get(0).get("versionNo").asInt());
        assertEquals("SUPERSEDED", versions.get(1).get("status").asText(), "旧口径版本置 SUPERSEDED 保留");

        // 重提 Y+2：新编号 + 按新口径 V2 + 独立生成时间 + 指向被撤回报表
        JsonNode rFutureSecond = generate(Y + 2, "统计员甲");
        long secondId = rFutureSecond.get("id").asLong();
        assertNotEquals(firstId, secondId, "撤回重提必须另出新报表");
        assertNotEquals(firstNo, rFutureSecond.get("reportNo").asText(), "重提编号须可辨");
        assertEquals(2, rFutureSecond.get("metricVersionNo").asInt(), "重提按新生效口径 V2");
        assertEquals(firstId, rFutureSecond.get("supersedesReportId").asLong(),
                "重提须指向被撤回的原报表");
        assertFalse(LocalDateTime.parse(rFutureSecond.get("generatedAt").asText()).isBefore(firstGeneratedAt),
                "重提生成时间须独立记录且不早于首版");
        JsonNode secondDetail = dataOf(getJson("/api/annual-reports/" + secondId));
        assertEquals("RESUBMIT",
                secondDetail.get("snapshots").get(0).get("snapshot").get("snapshotType").asText(),
                "重提出 RESUBMIT 快照");
        // 被撤回报表仍可查、编号不同
        assertEquals("WITHDRAWN",
                dataOf(getJson("/api/annual-reports/" + firstId)).get("report").get("status").asText());

        // ---- 归档 Y（V1 口径）----
        JsonNode archived = dataOf(postOk("/api/annual-reports/" + reportId + "/archive",
                "{\"operator\":\"馆长\"}"));
        assertEquals("ARCHIVED", archived.get("status").asText());
        assertFalse(archived.get("archivedAt").asText().isEmpty());

        // 归档为终态：不能再归档、不能撤回
        expectFail(postJson("/api/annual-reports/" + reportId + "/archive", "{\"operator\":\"馆长\"}"), "已归档");
        expectFail(postJson("/api/annual-reports/" + reportId + "/withdraw",
                "{\"operator\":\"馆长\",\"reason\":\"x\"}"), "仅未归档");

        // ---- 口径已先变更为 V2，但已归档 Y 报表仍按原版本 V1 核对 ----
        JsonNode recheck = dataOf(postOk("/api/annual-reports/" + reportId + "/recheck?operator=审计员", "{}"));
        assertEquals("RECHECK", recheck.get("snapshotType").asText());
        assertEquals(1, recheck.get("metricVersionNo").asInt(), "归档后核对必须沿用原口径 V1");
        assertEquals(2, recheck.get("anomalyCount").asInt(),
                "V2 已排除押运异常，但原报表按 V1 核对，异常仍为 2");

        // 归档后只追加新快照，原 ARCHIVED 快照数字冻结不改写
        JsonNode d2 = dataOf(getJson("/api/annual-reports/" + reportId));
        JsonNode snaps2 = d2.get("snapshots");
        assertEquals(3, snaps2.size(), "GENERATED/ARCHIVED/RECHECK 三张快照");
        assertEquals("GENERATED", snaps2.get(0).get("snapshot").get("snapshotType").asText());
        assertEquals("ARCHIVED", snaps2.get(1).get("snapshot").get("snapshotType").asText());
        assertEquals("RECHECK", snaps2.get(2).get("snapshot").get("snapshotType").asText());
        assertEquals(2, snaps2.get(1).get("snapshot").get("anomalyCount").asInt(), "归档快照数字冻结");

        // 异常凭证只追加不覆盖：生成/归档/核对三轮固化后仍只有 2 条（uk 幂等）
        JsonNode evidence = d2.get("evidence");
        assertEquals(2, evidence.size(), "同一凭证 uk 幂等，归档/核对不重复追加、不覆盖");

        // ---- Y+1 报表按新生效 V2：该年仅一条押运异常，V2 下异常=0 ----
        JsonNode rY1 = generate(Y + 1, "统计员甲");
        assertEquals(2, rY1.get("metricVersionNo").asInt(), "新年度报表按 V2");
        assertEquals(0, rY1.get("anomalyCount").asInt(), "V2 排除押运异常，该年异常事件为 0");

        // ---- 导出裁剪：按馆区 + 保密级别裁剪行与字段 ----
        String csvThreeMain = getCsv("/api/annual-reports/" + reportId
                + "/export?viewerCampus=MAIN&clearanceGrade=THREE");
        String[] linesThree = csvLines(csvThreeMain);
        assertEquals(2, linesThree.length, "表头 + MAIN 的 THREE 项目 P3 一行");
        assertFalse(linesThree[0].contains("保密级别"), "THREE 级别导出不应含保密级别列");
        assertFalse(linesThree[0].contains("委托人"), "THREE 级别导出不应含委托人列");
        assertTrue(linesThree[1].contains("MP-003"), "THREE 可见 MAIN 的 THREE 项目");
        assertFalse(csvThreeMain.contains("MP-001"), "ONE 密级项目对 THREE 不可见");
        assertFalse(csvThreeMain.contains("MP-004"), "EAST 馆区项目对 MAIN 视角不可见");

        String csvOneMain = getCsv("/api/annual-reports/" + reportId
                + "/export?viewerCampus=MAIN&clearanceGrade=ONE");
        String[] linesOneMain = csvLines(csvOneMain);
        assertEquals(4, linesOneMain.length, "表头 + MAIN 三项目（P1/P2/P3）");
        assertTrue(linesOneMain[0].contains("保密级别"), "ONE 级别导出含保密级别列");
        assertTrue(linesOneMain[0].contains("委托人"), "ONE 级别导出含委托人列");
        assertTrue(csvOneMain.contains("MP-001") && csvOneMain.contains("MP-002")
                && csvOneMain.contains("MP-003"));
        assertFalse(csvOneMain.contains("MP-004"), "MAIN 视角不见 EAST 馆区");

        String csvOneAll = getCsv("/api/annual-reports/" + reportId + "/export?clearanceGrade=ONE");
        assertEquals(5, csvLines(csvOneAll).length, "表头 + 全馆区四项目");
        assertTrue(csvOneAll.contains("MP-004"));

        JsonNode viewThree = dataOf(getJson("/api/annual-reports/" + reportId
                + "/export-view?viewerCampus=MAIN&clearanceGrade=THREE"));
        assertEquals(1, viewThree.get("projectRows").size());
        assertFalse(viewThree.get("columns").toString().contains("client"),
                "THREE 视图不应下发委托人字段");
        assertTrue(viewThree.get("columns").toString().contains("relicNo"));

        // ---- Quartz 真实触发（统计 Y）：幂等命中已归档报表，落 SCHEDULED 成功日志 ----
        quartzScheduler.triggerJob(org.quartz.JobKey.jobKey(
                com.evops.config.AnnualReportQuartzConfig.JOB_NAME, "MUSEUM_ANNUAL_REPORT"));
        assertTrue(awaitScheduledLog(Y, 10000), "Quartz 触发后应出现 Y 年 SCHEDULED 任务日志");

        // ---- 生成失败：失败日志 + OPEN 告警，不伪装成功；人工确认关闭 ----
        int openBefore = jobService.listAlerts("OPEN").size();
        AnnualReportJobService.RunResult failed = jobService.runGenerate(null, "MANUAL", "FIRE-FAIL-1", "测试员");
        assertFalse(failed.success, "生成失败必须返回失败而非伪装成功");
        assertEquals("FAILED", failed.jobLog.getStatus());
        assertNotNull(failed.jobLog.getErrorMessage(), "失败任务日志必须保留错误堆栈");
        assertNotNull(failed.alert, "失败必须产生告警");
        assertEquals("OPEN", failed.alert.getStatus());
        assertEquals(openBefore + 1, jobService.listAlerts("OPEN").size());

        // 失败不影响既有报表
        assertEquals("ARCHIVED",
                dataOf(getJson("/api/annual-reports/" + reportId)).get("report").get("status").asText());

        JsonNode acked = dataOf(postOk("/api/annual-reports/alerts/" + failed.alert.getId() + "/ack",
                "{\"acknowledgedBy\":\"值班员\",\"ackNote\":\"已排查并手工重跑\"}"));
        assertEquals("ACKNOWLEDGED", acked.get("status").asText());
        expectFail(postJson("/api/annual-reports/alerts/" + failed.alert.getId() + "/ack",
                "{\"acknowledgedBy\":\"值班员\"}"), "已确认");

        List<AnnualReportJobLog> logs = jobService.listJobLogs(null);
        assertTrue(logs.stream().anyMatch(l -> "SUCCESS".equals(l.getStatus()))
                        && logs.stream().anyMatch(l -> "FAILED".equals(l.getStatus())),
                "成功与失败任务日志都必须保留");
    }

    // ------------------------------------------------------------------
    // 造数
    // ------------------------------------------------------------------

    private void seedSourceData() {
        Relic r1 = relic("R-001", "青铜鼎", "ONE", "MAIN");
        Relic r2 = relic("R-002", "青花瓷瓶", "TWO", "MAIN");
        Relic r3 = relic("R-003", "书画手卷", "THREE", "MAIN");
        Relic r4 = relic("R-004", "东馆佛像", "TWO", "EAST");

        RepairProject p1 = project("MP-001", r1, "省考古所", TY, LocalDate.of(Y, 5, 20), "ACCEPTED");
        RepairProject p2 = project("MP-002", r2, "私人藏家", TY, LocalDate.of(Y, 7, 2), "REPAIRED");
        RepairProject p3 = project("MP-003", r3, "陈列部", TY, LocalDate.of(Y, 8, 8), "ACCEPTED");
        project("MP-004", r4, "东馆保管部", TY, LocalDate.of(Y, 9, 9), "ACCEPTED");
        passedReport(p1);
        passedReport(p3);
        // MP-002（REPAIRED 无 PASSED 成果报告）→ 待补；MP-004 也给通过报告
        RepairProject p4 = projectMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RepairProject>()
                        .eq(RepairProject::getProjectNo, "MP-004"));
        passedReport(p4);

        // 材料：P1 领用 100，退料 30 → 净消耗 70
        MaterialUsage usage = new MaterialUsage();
        usage.setUsageNo("MU-Y-1");
        usage.setProjectId(p1.getId());
        usage.setRelicId(r1.getId());
        usage.setUsageAt(TY);
        usage.setStatus("ISSUED");
        usageMapper.insert(usage);
        MaterialUsageLine line = new MaterialUsageLine();
        line.setUsageId(usage.getId());
        line.setBatchId(9001L);
        line.setMaterialId(9001L);
        line.setMaterialName("矿物颜料");
        line.setBatchNo("B-Y-1");
        line.setCategory("PIGMENT");
        line.setUnit("g");
        line.setPlanQty(new BigDecimal("100"));
        line.setActualQty(new BigDecimal("100"));
        line.setApplyPart("器身");
        line.setOperator("修复师甲");
        usageLineMapper.insert(line);
        MaterialReturn ret = new MaterialReturn();
        ret.setReturnNo("MR-Y-1");
        ret.setUsageId(usage.getId());
        ret.setLineId(line.getId());
        ret.setBatchId(9001L);
        ret.setProjectId(p1.getId());
        ret.setRelicId(r1.getId());
        ret.setReturnQty(new BigDecimal("30"));
        ret.setReturnedBy("修复师甲");
        ret.setReturnedAt(TY.plusDays(2));
        returnMapper.insert(ret);

        // 借展：Y 年签署两次；另一次 Y-1 年签署不计入
        signedAgreement("LA-Y-1", r1, 1, TY.minusMonths(1));
        signedAgreement("LA-Y-2", r2, 1, TY.plusMonths(1));
        signedAgreement("LA-Y-1-OLD", r3, 1, LocalDateTime.of(Y - 1, 5, 1, 9, 0));

        // 押运异常：A1 封箱 Y 年异常；A2 交接 Y+1 年异常
        escortAbnormal("LA-Y-1", "SEAL", TY.minusMonths(1), "封箱发现轻微碰撞");
        escortAbnormal("LA-Y-2", "HANDOVER", TY1, "交接时运输延误");

        // 设备故障 Y 年：设备经工时明细归属到 P1/R1
        RepairEquipment equip = new RepairEquipment();
        equip.setEquipmentNo("EQ-001");
        equip.setName("激光清洗仪");
        equip.setCategory("激光设备");
        equip.setStatus("ACTIVE");
        equipmentMapper.insert(equip);
        RepairTimesheet sheet = new RepairTimesheet();
        sheet.setProjectId(p1.getId());
        sheet.setVersionNo(1);
        sheet.setRestorer("修复师甲");
        sheet.setStatus("APPROVED");
        sheet.setSubmittedBy("修复师甲");
        sheet.setSubmittedAt(TY);
        timesheetMapper.insert(sheet);
        RepairTimesheetLine tsLine = new RepairTimesheetLine();
        tsLine.setTimesheetId(sheet.getId());
        tsLine.setLineNo(1);
        tsLine.setWorkStep("激光清洗");
        tsLine.setEquipmentId(equip.getId());
        tsLine.setStartTime(TY.minusHours(2));
        tsLine.setEndTime(TY.plusHours(2));
        timesheetLineMapper.insert(tsLine);
        RepairEquipmentFault fault = new RepairEquipmentFault();
        fault.setFaultNo("FT-Y-1");
        fault.setEquipmentId(equip.getId());
        fault.setDescription("激光器功率异常中断");
        fault.setFaultAt(TY);
        fault.setExpectedRecoveredAt(TY.plusDays(1));
        fault.setStatus("CLOSED");
        faultMapper.insert(fault);

        // Y+2 一个立项（撤回重提场景）
        project("MP-FUTURE-1", r1, "未来委托方", TY2, null, "PLANNED");
    }

    private Relic relic(String no, String name, String grade, String campus) {
        Relic r = new Relic();
        r.setRelicNo(no);
        r.setName(name);
        r.setMaterial("青铜");
        r.setEra("西周");
        r.setSource("考古移交");
        r.setPreservationGrade(grade);
        r.setCampusCode(campus);
        r.setStatus("ACTIVE");
        relicMapper.insert(r);
        return r;
    }

    private RepairProject project(String no, Relic relic, String client,
                                  LocalDateTime createTime, LocalDate finishDate, String status) {
        RepairProject p = new RepairProject();
        p.setProjectNo(no);
        p.setRelicId(relic.getId());
        p.setClient(client);
        p.setPlan("修复方案文本");
        p.setFinishDate(finishDate);
        p.setStatus(status);
        p.setCreateTime(createTime);
        projectMapper.insert(p);
        return p;
    }

    private void passedReport(RepairProject p) {
        AcceptReport r = new AcceptReport();
        r.setProjectId(p.getId());
        r.setRoundNo(1);
        r.setCraft("清洗");
        r.setSummary("成果报告");
        r.setExpertName("专家甲");
        r.setCustodianName("保管员甲");
        r.setSubmittedBy("修复师甲");
        r.setSubmittedAt(TY);
        r.setStatus("PASSED");
        acceptReportMapper.insert(r);
    }

    private void signedAgreement(String agreementNo, Relic relic, int versionNo, LocalDateTime signedAt) {
        LoanAgreement ag = new LoanAgreement();
        ag.setAgreementNo(agreementNo);
        ag.setRelicId(relic.getId());
        ag.setPurpose("展览");
        ag.setStatus("SIGNED");
        agreementMapper.insert(ag);
        LoanAgreementVersion v = new LoanAgreementVersion();
        v.setAgreementId(ag.getId());
        v.setVersionNo(versionNo);
        v.setBorrower("某博物馆");
        v.setInsuranceAmount(new BigDecimal("100000"));
        v.setTransportRoute("馆内专车押运");
        v.setReturnDate(signedAt.toLocalDate().plusMonths(3));
        v.setStatus("SIGNED");
        v.setSignedBy("经办人");
        v.setSignedAt(signedAt);
        v.setTerms("条款");
        agreementVersionMapper.insert(v);
        ag.setCurrentVersionId(v.getId());
        agreementMapper.updateById(ag);
    }

    private void escortAbnormal(String agreementNo, String nodeType, LocalDateTime at, String reason) {
        LoanAgreement ag = agreementMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LoanAgreement>()
                        .eq(LoanAgreement::getAgreementNo, agreementNo));
        LoanAgreementVersion v = agreementVersionMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LoanAgreementVersion>()
                        .eq(LoanAgreementVersion::getAgreementId, ag.getId()).last("LIMIT 1"));
        EscortNode n = new EscortNode();
        n.setAgreementId(ag.getId());
        n.setAgreementVersionId(v.getId());
        n.setNodeType(nodeType);
        n.setSeqNo(1);
        n.setHandler("押运员");
        n.setRecordedAt(at);
        n.setAbnormal(1);
        n.setAnomalyReason(reason);
        escortNodeMapper.insert(n);
    }

    private boolean awaitScheduledLog(int year, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            boolean exists = jobLogMapper.selectList(
                            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AnnualReportJobLog>()
                                    .eq(AnnualReportJobLog::getReportYear, year)
                                    .eq(AnnualReportJobLog::getTriggerType, "SCHEDULED"))
                    .stream().findAny().isPresent();
            if (exists) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    // ------------------------------------------------------------------
    // HTTP helpers
    // ------------------------------------------------------------------

    private JsonNode generate(int year, String operator) throws Exception {
        return dataOf(postOk("/api/annual-reports/generate",
                "{\"year\":" + year + ",\"operator\":\"" + operator + "\"}")).get("report");
    }

    private MvcResult postOk(String uri, String body) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult postJson(String uri, String body) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult getJson(String uri) throws Exception {
        return mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn();
    }

    private String getCsv(String uri) throws Exception {
        return mockMvc.perform(get(uri)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String[] csvLines(String csv) {
        if (csv.charAt(0) == '﻿') {
            csv = csv.substring(1);
        }
        return csv.split("\n");
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data");
    }

    private void expectFail(MvcResult result, String contains) throws Exception {
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertFalse(root.get("success").asBoolean());
        assertTrue(root.get("message").asText().contains(contains),
                "失败信息应包含[" + contains + "]，实际：" + root.get("message").asText());
    }
}
