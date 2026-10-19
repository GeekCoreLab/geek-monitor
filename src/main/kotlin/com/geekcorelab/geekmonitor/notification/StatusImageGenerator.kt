package com.geekcorelab.geekmonitor.notification

import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class NotificationStatusEntry(
    val title: String,
    val resourceName: String,
    val monitorName: String,
    val eventType: String,
    val category: String?,
    val severity: String?,
    val eventStatus: String?,
    val incidentStatus: String?,
    val currentMonitorState: String,
    val resourceState: String?,
    val latestCheckStatus: String?,
    val checkedAt: Instant?,
    val alertMessage: String?,
    val alertErrorCode: String?,
    val latestErrorCode: String?,
    val latestErrorSummary: String?,
    val httpStatus: Int?,
    val durationMs: Long?,
    val consecutiveFailures: Int?,
    val failureThreshold: Int?,
    val incidentOpenedAt: Instant?,
    val occurrenceCount: Int?,
)

@ApplicationScoped
class StatusImageGenerator {
    fun render(entries: List<NotificationStatusEntry>, generatedAt: Instant = Instant.now()): ByteArray {
        val ordered = entries.sortedWith(compareByDescending<NotificationStatusEntry> { severityRank(it.severity) }
            .thenBy { stateRank(it.currentMonitorState) }
            .thenBy { it.resourceName.lowercase() })
        val visible = ordered.take(MAX_VISIBLE_ENTRIES)
        val rowHeight = 154
        val height = HEADER_HEIGHT + visible.size * rowHeight + FOOTER_HEIGHT
        val svg = buildString {
            appendLine("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$WIDTH\" height=\"$height\" viewBox=\"0 0 $WIDTH $height\">")
            appendLine("<rect width=\"100%\" height=\"100%\" rx=\"24\" fill=\"#f1f5f9\"/>")
            appendLine("<rect x=\"0\" y=\"0\" width=\"$WIDTH\" height=\"142\" rx=\"24\" fill=\"#0f172a\"/>")
            appendLine("<rect x=\"0\" y=\"118\" width=\"$WIDTH\" height=\"24\" fill=\"#0f172a\"/>")
            text(40, 44, "GEEK MONITOR  /  INCIDENT SNAPSHOT", 14, "#94a3b8", 700, tracking = 1.4)
            text(40, 86, "告警状态快照", 30, "#f8fafc", 700)
            text(40, 116, "投递时生成 · 北京时间 ${formatTime(generatedAt)}", 14, "#cbd5e1")
            text(WIDTH - 40, 84, "${entries.size} 条事件", 18, "#e2e8f0", 600, anchor = "end")

            visible.forEachIndexed { index, entry ->
                val top = HEADER_HEIGHT + index * rowHeight
                val stateColor = colorForState(entry.currentMonitorState)
                appendLine("<rect x=\"24\" y=\"$top\" width=\"${WIDTH - 48}\" height=\"138\" rx=\"16\" fill=\"#ffffff\" stroke=\"#dbe4ee\"/>")
                appendLine("<rect x=\"24\" y=\"$top\" width=\"7\" height=\"138\" rx=\"3\" fill=\"$stateColor\"/>")
                text(48, top + 32, entry.resourceName.ifBlank { "未命名资源" }.shorten(23), 20, "#0f172a", 700)
                text(48, top + 58, "${entry.monitorName.ifBlank { "监控项" }.shorten(32)}  ·  ${categoryLabel(entry.category)}  ·  ${eventLabel(entry.eventType)}", 14, "#64748b")
                stateBadge(top + 16, entry.currentMonitorState)

                val check = entry.latestCheckStatus?.let(::stateLabel) ?: "暂无检查结果"
                val checkTime = entry.checkedAt?.let(::formatTime) ?: "时间未知"
                val details = buildList {
                    add("最新检查：$check · $checkTime")
                    entry.durationMs?.let { add("${it}ms") }
                    entry.httpStatus?.let { add("HTTP $it") }
                    entry.consecutiveFailures?.let { failures ->
                        entry.failureThreshold?.let { threshold -> add("连续失败 $failures/$threshold") }
                    }
                    entry.occurrenceCount?.takeIf { it > 1 }?.let { add("累计 ${it} 次") }
                    entry.incidentOpenedAt?.let { opened -> add("持续 ${formatDuration(Duration.between(opened, generatedAt))}") }
                }
                text(48, top + 86, details.joinToString("  ·  ").shorten(34), 14, "#334155")

                val diagnostic = when {
                    !entry.latestErrorSummary.isNullOrBlank() -> entry.latestErrorSummary
                    !entry.latestErrorCode.isNullOrBlank() -> "最新检查错误：${entry.latestErrorCode}"
                    !entry.alertMessage.isNullOrBlank() -> entry.alertMessage
                    !entry.alertErrorCode.isNullOrBlank() -> "告警错误：${entry.alertErrorCode}"
                    else -> "最新检查未返回错误信息"
                }
                text(48, top + 116, "原因：${diagnostic.shorten(36)}", 14, "#475569")
                val incidentLabel = when (entry.incidentStatus) {
                    "ACKNOWLEDGED" -> "处理中"
                    "OPEN" -> "待处理"
                    "RESOLVED" -> "已解决"
                    else -> entry.eventStatus?.let(::stateLabel) ?: "事件状态未知"
                }
                text(WIDTH - 48, top + 116, "事件 $incidentLabel", 13, "#64748b", anchor = "end")
            }

            val omitted = (ordered.size - visible.size).coerceAtLeast(0)
            val footerY = HEADER_HEIGHT + visible.size * rowHeight + 28
            val footerText = if (omitted > 0) "另有 $omitted 条事件未放入图片，完整列表见邮件正文。" else "状态来自投递时的最新检查记录；凭据和监控目标配置不会写入图片。"
            text(32, footerY, footerText, 13, "#64748b")
            appendLine("</svg>")
        }
        return svg.toByteArray(Charsets.UTF_8)
    }

    fun plainSummary(entries: List<NotificationStatusEntry>): String = buildString {
        appendLine("投递时状态快照（北京时间）：")
        entries.forEachIndexed { index, entry ->
            appendLine("${index + 1}. ${entry.resourceName} / ${entry.monitorName}：当前 ${stateLabel(entry.currentMonitorState)}，资源 ${entry.resourceState?.let(::stateLabel) ?: "未知"}，最近检查 ${entry.latestCheckStatus?.let(::stateLabel) ?: "无数据"} ${entry.checkedAt?.let(::formatTime) ?: ""}")
            val reason = entry.latestErrorSummary ?: entry.latestErrorCode?.let { "错误码 $it" } ?: entry.alertMessage
            if (!reason.isNullOrBlank()) appendLine("   原因：${reason.shorten(220)}")
            entry.httpStatus?.let { appendLine("   HTTP 状态：$it") }
            entry.durationMs?.let { appendLine("   检查耗时：${it}ms") }
            if (entry.consecutiveFailures != null && entry.failureThreshold != null) {
                appendLine("   连续失败：${entry.consecutiveFailures}/${entry.failureThreshold}")
            }
        }
    }.trimEnd()

    fun htmlSummary(entries: List<NotificationStatusEntry>): String = buildString {
        append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"border-collapse:collapse;width:100%;font:14px Arial,sans-serif\">")
        entries.forEach { entry ->
            val current = stateLabel(entry.currentMonitorState)
            val check = entry.latestCheckStatus?.let(::stateLabel) ?: "暂无检查结果"
            val reason = entry.latestErrorSummary ?: entry.latestErrorCode ?: entry.alertMessage.orEmpty()
            append("<tr><td style=\"padding:12px;border:1px solid #dbe4ee\"><strong>${escapeHtml(entry.resourceName)} · ${escapeHtml(entry.monitorName)}</strong>")
            append("<br>当前状态：${escapeHtml(current)}　资源：${escapeHtml(entry.resourceState?.let(::stateLabel) ?: "未知")}　最近检查：${escapeHtml(check)}")
            append("<br>检查时间：${escapeHtml(entry.checkedAt?.let(::formatTime) ?: "未知")}　HTTP：${entry.httpStatus?.toString() ?: "—"}　耗时：${entry.durationMs?.let { "${it}ms" } ?: "—"}")
            if (reason.isNotBlank()) append("<br>原因：${escapeHtml(reason.shorten(260))}")
            if (entry.consecutiveFailures != null && entry.failureThreshold != null) append("<br>连续失败：${entry.consecutiveFailures}/${entry.failureThreshold}")
            append("</td></tr>")
        }
        append("</table>")
    }

    fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun StringBuilder.text(
        x: Int,
        y: Int,
        value: String,
        size: Int,
        color: String,
        weight: Int = 400,
        anchor: String = "start",
        tracking: Double = 0.0,
    ) {
        appendLine("<text x=\"$x\" y=\"$y\" fill=\"$color\" font-family=\"Arial, 'Noto Sans CJK SC', sans-serif\" font-size=\"$size\" font-weight=\"$weight\" text-anchor=\"$anchor\" letter-spacing=\"$tracking\">${escapeXml(value)}</text>")
    }

    private fun StringBuilder.stateBadge(top: Int, status: String) {
        val label = stateLabel(status)
        val color = colorForState(status)
        appendLine("<rect x=\"${WIDTH - 214}\" y=\"$top\" width=\"166\" height=\"36\" rx=\"18\" fill=\"${color}22\"/>")
        text(WIDTH - 131, top + 24, label, 14, color, 700, anchor = "middle")
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun String.shorten(maxWidthUnits: Int): String {
        if (isEmpty()) return this
        val result = StringBuilder()
        var used = 0.0
        for (character in this) {
            val width = if (character.code >= 0x2E80) 1.0 else 0.55
            if (used + width > maxWidthUnits - 1) return result.append('…').toString()
            result.append(character)
            used += width
        }
        return result.toString()
    }

    private fun stateLabel(status: String): String = when (status.uppercase()) {
        "DOWN" -> "故障 DOWN"
        "DEGRADED" -> "降级 DEGRADED"
        "UP" -> "正常 UP"
        "UNKNOWN" -> "未知 UNKNOWN"
        "PENDING" -> "待检查 PENDING"
        "RUNNING" -> "检查中 RUNNING"
        else -> status.ifBlank { "未知" }
    }

    private fun colorForState(status: String): String = when (status.uppercase()) {
        "DOWN" -> "#dc2626"
        "DEGRADED" -> "#d97706"
        "UP" -> "#059669"
        else -> "#64748b"
    }

    private fun categoryLabel(category: String?): String = when (category?.uppercase()) {
        "AVAILABILITY" -> "可用性"
        "PERFORMANCE" -> "性能阈值"
        "COLLECTION" -> "采集链路"
        "TLS_CERTIFICATE" -> "TLS 证书"
        else -> category ?: "监控事件"
    }

    private fun eventLabel(eventType: String): String = when (eventType.uppercase()) {
        "OPENED" -> "新告警"
        "ESCALATED" -> "升级"
        "REMINDER" -> "持续提醒"
        "RESOLVED" -> "恢复通知"
        else -> eventType
    }

    private fun severityRank(severity: String?): Int = when (severity?.uppercase()) {
        "CRITICAL" -> 4
        "HIGH" -> 3
        "WARNING" -> 2
        "INFO" -> 1
        else -> 0
    }

    private fun stateRank(state: String): Int = when (state.uppercase()) {
        "DOWN" -> 4
        "DEGRADED" -> 3
        "UNKNOWN" -> 2
        "PENDING" -> 1
        else -> 0
    }

    private fun formatTime(instant: Instant): String = TIME_FORMAT.format(instant)

    private fun formatDuration(duration: Duration): String {
        val minutes = duration.toMinutes().coerceAtLeast(0)
        val days = minutes / 1_440
        val hours = minutes % 1_440 / 60
        val remainingMinutes = minutes % 60
        return when {
            days > 0 -> "${days}天${hours}小时"
            hours > 0 -> "${hours}小时${remainingMinutes}分"
            else -> "${remainingMinutes}分钟"
        }
    }

    companion object {
        private const val WIDTH = 760
        private const val HEADER_HEIGHT = 164
        private const val FOOTER_HEIGHT = 54
        private const val MAX_VISIBLE_ENTRIES = 8
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"))
    }
}
