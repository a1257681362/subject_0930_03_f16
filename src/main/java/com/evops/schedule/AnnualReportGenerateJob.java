package com.evops.schedule;

import com.evops.service.AnnualReportJobService;
import org.quartz.JobExecutionContext;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.quartz.QuartzJobBean;

import java.time.LocalDate;

/**
 * 文物修复年度报表 Quartz 生成任务（museum-16）。
 *
 * <p>每年由 CronTrigger 触发一次，统计区间为<b>上一完整自然年度</b>。
 * Job 为无状态薄壳：业务生成、任务日志、失败告警全部委托
 * {@link AnnualReportJobService}——失败由其落 FAILED 日志与 OPEN 告警，
 * 此处不再吞异常、也不伪装成功。通过调度器上下文获取 Spring 容器，
 * 保证 Job 由 Quartz 实例化也能取到受管服务。</p>
 */
public class AnnualReportGenerateJob extends QuartzJobBean {

    public static final String APPLICATION_CONTEXT_KEY = "applicationContext";
    public static final String OPERATOR_SYSTEM = "QUARTZ_SYSTEM";

    @Override
    protected void executeInternal(JobExecutionContext context) {
        try {
            ApplicationContext ctx = (ApplicationContext) context.getScheduler()
                    .getContext().get(APPLICATION_CONTEXT_KEY);
            AnnualReportJobService jobService = ctx.getBean(AnnualReportJobService.class);
            int year = LocalDate.now().getYear() - 1;
            jobService.runGenerate(year, "SCHEDULED",
                    context.getFireInstanceId(), OPERATOR_SYSTEM);
        } catch (Exception e) {
            // 兜底：理论上 runGenerate 已内部捕获并落日志告警，此处仅避免 Quartz 误判
            throw new IllegalStateException("年度报表 Quartz 任务执行异常", e);
        }
    }
}
