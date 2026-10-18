package com.geekcorelab.geekmonitor.monitor

data class RetentionPolicy(val key: String, val label: String, val retentionDays: Int)

object RetentionPolicyCatalog {
    const val DAY_SECONDS = 24 * 60 * 60L
    const val RAW_METRIC_DAYS = 3
    const val FIVE_MINUTE_ROLLUP_DAYS = 30
    const val HOURLY_ROLLUP_DAYS = 180
    const val CHECK_EXECUTION_DAYS = 7
    const val AGENT_BATCH_DAYS = 30
    const val EVENT_DAYS = 180
    const val AVAILABILITY_DAYS = 180

    val policies = listOf(
        RetentionPolicy("checkExecutions", "主动检查结果", CHECK_EXECUTION_DAYS),
        RetentionPolicy("rawMetrics", "原始主机与数据库指标", RAW_METRIC_DAYS),
        RetentionPolicy("fiveMinuteMetrics", "5 分钟指标聚合", FIVE_MINUTE_ROLLUP_DAYS),
        RetentionPolicy("hourlyMetrics", "小时指标聚合", HOURLY_ROLLUP_DAYS),
        RetentionPolicy("availability", "可用率小时统计", AVAILABILITY_DAYS),
        RetentionPolicy("agentBatches", "Agent 批次去重记录", AGENT_BATCH_DAYS),
        RetentionPolicy("eventsAndAudit", "已结束故障、终态通知与审计", EVENT_DAYS),
    )
}
