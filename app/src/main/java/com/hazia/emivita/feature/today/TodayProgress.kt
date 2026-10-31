package com.hazia.emivita.feature.today

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hazia.emivita.R
import com.hazia.emivita.core.format.LocaleFormats
import com.hazia.emivita.core.scheduling.ProjectionState

internal const val TODAY_PROGRESS_TAG = "today-progress"

/** Today's planned doses at a glance (§7 R135): how many of them are taken, how many are due now, and the next one still to come. */
internal data class TodayProgress(val taken: Int, val planned: Int, val dueNow: Int, val nextAt: Long?, val nextEstimated: Boolean = false)

/**
 * A whole projection pass: the only state the line may read an absence from. "Nothing left today" and the "of 3"
 * both are one, so a first frame or a pass still running must not draw them (the cache's own rule, §9 #31).
 */
internal val ProjectionState.settled: Boolean get() = computedAtEpochMillis > 0L && !inProgress && !truncated

/**
 * Only the doses a reminder planned count, as in History's figures (§7 R111): a logged extra or a one-off is on
 * the timeline but not in the plan. Null when today plans nothing, or while the projection is not [settled], so
 * the line is not drawn.
 */
internal fun todayProgress(nodes: List<TimelineNode>, settled: Boolean = true): TodayProgress? {
    if (!settled) return null
    val planned = flatten(nodes).filter { it.second.reminderId != null }
    if (planned.isEmpty()) return null
    val next = planned.filter { it.second.status == TimelineStatus.UPCOMING }.minByOrNull { it.first.epochMillis }
    return TodayProgress(
        taken = planned.count { it.second.status == TimelineStatus.TAKEN },
        planned = planned.size,
        dueNow = planned.count { it.second.status == TimelineStatus.PENDING },
        nextAt = next?.first?.epochMillis,
        nextEstimated = next?.second?.estimated == true,
    )
}

/**
 * The count Today shows: read from a whole pass, and held while the next one runs. Every Take starts a pass that
 * publishes medicine by medicine, and a line that hid for each of them shifted the page twice per answer.
 */
internal class HeldProgress {
    private var last: TodayProgress? = null

    fun of(nodes: List<TimelineNode>, settled: Boolean): TodayProgress? =
        if (settled) todayProgress(nodes).also { last = it } else last
}

/** "1 di 3 prese · prossima alle 20:45" under Today's title (§7 R135, the owner's pick). */
@Composable
internal fun TodayProgressLine(progress: TodayProgress) {
    val count = pluralStringResource(R.plurals.doses_taken_of, progress.planned, progress.taken, progress.planned)
    val tail = when {
        progress.dueNow > 0 -> pluralStringResource(R.plurals.today_progress_due, progress.dueNow, progress.dueNow)
        progress.nextAt != null -> stringResource(
            R.string.today_progress_next,
            LocaleFormats.time(progress.nextAt).let { if (progress.nextEstimated) "≈ $it" else it },
        )
        else -> stringResource(R.string.today_progress_done)
    }
    Text(
        stringResource(R.string.today_progress_line, count, tail),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp).testTag(TODAY_PROGRESS_TAG),
    )
}
