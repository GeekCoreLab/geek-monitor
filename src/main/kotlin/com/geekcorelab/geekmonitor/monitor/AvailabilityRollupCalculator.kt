package com.geekcorelab.geekmonitor.monitor

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

internal data class AvailabilitySpan(val start: Instant, val end: Instant)

internal data class AvailabilityExecution(
    val scheduledAt: Instant,
    val intervalSeconds: Int,
    val status: String,
)

internal data class AvailabilityBucketTotals(
    val scheduledSeconds: Int,
    val upSeconds: Int,
    val degradedSeconds: Int,
    val downSeconds: Int,
    val unknownSeconds: Int,
    val excludedSeconds: Int,
)

internal fun calculateAvailabilityBucketTotals(
    activePeriods: List<AvailabilitySpan>,
    monitored: List<AvailabilitySpan>,
    excluded: List<AvailabilitySpan>,
    pauses: List<AvailabilitySpan>,
    executions: List<AvailabilityExecution>,
): AvailabilityBucketTotals {
    val excludedUnion = mergeAvailabilitySpans(excluded)
    val pauseUnion = mergeAvailabilitySpans(pauses)
    val runs = executions.sortedBy(AvailabilityExecution::scheduledAt)
    var up = 0.0
    var degraded = 0.0
    var down = 0.0

    runs.forEachIndexed { index, execution ->
        val activeWindow = activePeriods.firstOrNull {
            !execution.scheduledAt.isBefore(it.start) && execution.scheduledAt.isBefore(it.end)
        } ?: return@forEachIndexed
        if (pauseUnion.any { !execution.scheduledAt.isBefore(it.start) && execution.scheduledAt.isBefore(it.end) }) {
            return@forEachIndexed
        }

        val intervalEnd = execution.scheduledAt.plusSeconds(execution.intervalSeconds.toLong())
        val nextAt = runs.getOrNull(index + 1)?.scheduledAt?.takeIf { it.isBefore(activeWindow.end) }
        val nextPauseAt = pauseUnion.asSequence()
            .map(AvailabilitySpan::start)
            .filter { it.isAfter(execution.scheduledAt) && it.isBefore(activeWindow.end) }
            .minOrNull()
        // A result carries for at most one configured interval and never across a disabled or paused period.
        val end = minOf(intervalEnd, nextAt ?: intervalEnd, nextPauseAt ?: intervalEnd, activeWindow.end)
        if (!end.isAfter(execution.scheduledAt)) return@forEachIndexed

        val inBucket = intersectAvailabilitySpans(listOf(AvailabilitySpan(execution.scheduledAt, end)), monitored)
        val seconds = durationSeconds(subtractAvailabilitySpans(inBucket, excludedUnion))
        when (execution.status) {
            "UP" -> up += seconds
            "DEGRADED" -> {
                up += seconds
                degraded += seconds
            }
            "DOWN" -> down += seconds
        }
    }

    val scheduledSeconds = durationSeconds(monitored).toInt().coerceIn(0, HOUR_SECONDS.toInt())
    val excludedSeconds = durationSeconds(excludedUnion).roundToInt().coerceIn(0, scheduledSeconds)
    val eligibleSeconds = (scheduledSeconds - excludedSeconds).coerceAtLeast(0)
    val upSeconds = up.roundToInt().coerceIn(0, eligibleSeconds)
    val downSeconds = down.roundToInt().coerceIn(0, eligibleSeconds - upSeconds)
    return AvailabilityBucketTotals(
        scheduledSeconds = scheduledSeconds,
        upSeconds = upSeconds,
        degradedSeconds = degraded.roundToInt().coerceIn(0, upSeconds),
        downSeconds = downSeconds,
        unknownSeconds = eligibleSeconds - upSeconds - downSeconds,
        excludedSeconds = excludedSeconds,
    )
}

internal fun mergeAvailabilitySpans(spans: List<AvailabilitySpan>): List<AvailabilitySpan> {
    val sorted = spans.filter { it.end.isAfter(it.start) }.sortedBy(AvailabilitySpan::start)
    if (sorted.isEmpty()) return emptyList()
    val merged = mutableListOf(sorted.first())
    sorted.drop(1).forEach { span ->
        val previous = merged.last()
        if (!span.start.isAfter(previous.end)) {
            merged[merged.lastIndex] = AvailabilitySpan(previous.start, maxOf(previous.end, span.end))
        } else {
            merged += span
        }
    }
    return merged
}

internal fun intersectAvailabilitySpans(
    left: List<AvailabilitySpan>,
    right: List<AvailabilitySpan>,
): List<AvailabilitySpan> = buildList {
    left.forEach { a ->
        right.forEach { b ->
            val start = maxOf(a.start, b.start)
            val end = minOf(a.end, b.end)
            if (end.isAfter(start)) add(AvailabilitySpan(start, end))
        }
    }
}

private fun subtractAvailabilitySpans(
    source: List<AvailabilitySpan>,
    exclusions: List<AvailabilitySpan>,
): List<AvailabilitySpan> {
    val cuts = mergeAvailabilitySpans(exclusions)
    return buildList {
        source.forEach { span ->
            var cursor = span.start
            cuts.filter { it.end.isAfter(span.start) && it.start.isBefore(span.end) }.forEach { cut ->
                val cutStart = maxOf(cut.start, span.start)
                val cutEnd = minOf(cut.end, span.end)
                if (cutStart.isAfter(cursor)) add(AvailabilitySpan(cursor, cutStart))
                if (cutEnd.isAfter(cursor)) cursor = cutEnd
            }
            if (span.end.isAfter(cursor)) add(AvailabilitySpan(cursor, span.end))
        }
    }
}

private fun durationSeconds(spans: List<AvailabilitySpan>): Double = spans.sumOf { span ->
    val duration = Duration.between(span.start, span.end)
    duration.seconds.toDouble() + duration.nano / 1_000_000_000.0
}

private const val HOUR_SECONDS = 3600L
