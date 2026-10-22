package com.hazia.emivita.feature.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.height
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.hazia.emivita.EmivitaApplication
import com.hazia.emivita.MainActivity
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.dao.displayName
import androidx.glance.LocalContext
import com.hazia.emivita.R
import com.hazia.emivita.core.di.AppContainer
import com.hazia.emivita.feature.alarm.TakeAmountActivity
import com.hazia.emivita.core.format.LocaleFormats
import com.hazia.emivita.core.format.maskedMedicineName
import kotlinx.coroutines.flow.first

/** How far back the last answered dose is looked for, and how far ahead a dose answered early may sit. */
private const val LAST_DOSE_WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1000

internal data class NextReminderRow(
    val reminderId: Long,
    val medicineId: Long,
    val medicineName: String,
    val epochMillis: Long,
    val doseAmount: Double,
    /** §9 #31 (A-24): a follow-up projected off its parent's slot, shown as "≈". */
    val estimated: Boolean = false,
)

/** A dose answered Taken or Skipped: the line above the upcoming rows (§7 R108, census E-40). */
internal data class AnsweredDose(val medicineName: String, val status: DoseStatus, val atEpochMillis: Long)

/** What the Next-doses widget draws: the upcoming rows, as many as a tall widget can show, and the last answer. */
internal data class NextDosesData(val rows: List<NextReminderRow>, val last: AnsweredDose?)

/**
 * Home-screen widget for spec §9 #7 -- reads [com.hazia.emivita.core.scheduling.OccurrenceProjectionCache]'s
 * already-computed [com.hazia.emivita.core.scheduling.ProjectionState.occurrences] rather than
 * running its own simulation, matching the cache's whole reason for existing
 * (Today/History/Widgets all sharing one projection pass -- see the cache's
 * own doc comment).
 *
 * Refreshed after every finished projection pass (§7 R104, as medTimer's
 * widget follows its simulation), through a callback AppContainer hands the
 * cache, so core/scheduling still knows nothing of widgets; the 30-minute
 * `android:updatePeriodMillis` stays as the backstop for the clock alone.
 * Composed at the size the launcher reports (§7 R108, census E-38), so its
 * rows fill the size the owner picks; names follow hide-names (E-36).
 */
class NextRemindersWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as EmivitaApplication).container
        val data = loadNextDoses(container, System.currentTimeMillis())
        val palette = container.settingsRepository.widgetPalette()
        provideContent {
            WidgetTheme(palette) {
                NextRemindersContent(data)
            }
        }
    }
}

internal suspend fun loadNextDoses(container: AppContainer, now: Long): NextDosesData {
    val state = container.occurrenceProjectionCache.state.value
    val hide = container.settingsRepository.hideMedicineNames.first()

    // Plain List chain, not asSequence() -- Sequence's mapNotNull stores the
    // transform as a real function object rather than inlining it, which
    // can't call a suspend function (getById) the way List's inline
    // mapNotNull below can.
    val candidates = state.occurrences
        .filter { it.epochMillis >= now }
        .sortedBy { it.epochMillis }
        .distinctBy { it.reminderId } // one row per reminder -- its soonest occurrence
        .take(MAX_WIDGET_ROWS)

    val rows = candidates.mapNotNull { occurrence ->
        val medicine = container.medicineRepository.getById(occurrence.medicineId) ?: return@mapNotNull null
        NextReminderRow(
            reminderId = occurrence.reminderId,
            medicineId = occurrence.medicineId,
            medicineName = maskedMedicineName(medicine.name, hide),
            epochMillis = occurrence.epochMillis,
            doseAmount = occurrence.doseAmount,
            estimated = occurrence.estimated,
        )
    }
    return NextDosesData(rows, lastAnsweredDose(container, now, hide))
}

/**
 * The dose most recently answered Taken or Skipped, of every profile, as the
 * rows are. Slots up to a week either side of [now] are read, because Take now
 * answers a slot still to come (§7 R65).
 */
private suspend fun lastAnsweredDose(container: AppContainer, now: Long, hide: Boolean): AnsweredDose? =
    container.profileRepository.observeAll().first()
        .flatMap { container.doseEventRepository.getInRangeOnce(it.id, now - LAST_DOSE_WINDOW_MILLIS, now + LAST_DOSE_WINDOW_MILLIS, null) }
        .map { it.doseEvent to it.displayName }
        .filter { (e, _) -> (e.status == DoseStatus.TAKEN || e.status == DoseStatus.SKIPPED) && (e.resolvedAtEpochMillis ?: Long.MAX_VALUE) <= now }
        .maxByOrNull { (e, _) -> e.resolvedAtEpochMillis!! }
        ?.let { (e, m) -> AnsweredDose(maskedMedicineName(m, hide), e.status, e.resolvedAtEpochMillis!!) }

@Composable
internal fun NextRemindersContent(data: NextDosesData) {
    val context = LocalContext.current
    val size = LocalSize.current
    val metrics = WidgetMetrics(context, size.width.value, size.height.value)
    val header = context.getString(R.string.widget_next_doses_header)
    val lastText = data.last?.let { last ->
        val res = if (last.status == DoseStatus.SKIPPED) R.string.widget_last_skipped else R.string.widget_last_taken
        context.getString(res, last.medicineName, LocaleFormats.time(last.atEpochMillis))
    }
    val take = context.getString(R.string.common_take)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.background)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Text(
            header,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onBackground),
        )
        Spacer(modifier = GlanceModifier.height(4.dp))
        val lastLines = lastText?.let { metrics.lines(it, 11f, metrics.rowTextWidth(null)) } ?: 0
        if (lastText != null) {
            Text(
                lastText,
                modifier = GlanceModifier.padding(bottom = 2.dp),
                style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
                maxLines = lastLines,
            )
        }
        if (data.rows.isEmpty()) {
            Text(
                context.getString(R.string.widget_all_caught_up),
                style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant),
            )
        } else {
            val above = metrics.headerDp(header) + (lastText?.let { metrics.textDp(it, 11f, metrics.rowTextWidth(null)) + 2f } ?: 0f)
            val heights = data.rows.mapIndexed { index, row -> metrics.rowDp(row.medicineName, rowTime(row), if (index == 0) take else null) }
            WidgetRows(data.rows.take(metrics.rowsThatFit(above, heights))) { index, row ->
                NextReminderRowItem(row, metrics, showTakeAction = index == 0)
            }
        }
    }
}

private fun rowTime(row: NextReminderRow) = LocaleFormats.time(row.epochMillis).let { if (row.estimated) "≈ $it" else it }

@Composable
private fun NextReminderRowItem(row: NextReminderRow, metrics: WidgetMetrics, showTakeAction: Boolean) {
    val take = LocalContext.current.getString(R.string.common_take)
    val time = rowTime(row)
    val width = metrics.rowTextWidth(if (showTakeAction) take else null)
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(row.medicineName, style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurface), maxLines = metrics.lines(row.medicineName, 13f, width))
            Text(time, style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant), maxLines = metrics.lines(time, 11f, width))
        }
        if (showTakeAction) {
            WidgetActionPill(
                take,
                actionRunCallback<TakeNextDoseAction>(
                    actionParametersOf(
                        ReminderIdKey to row.reminderId,
                        MedicineIdKey to row.medicineId,
                        EpochMillisKey to row.epochMillis,
                        DoseAmountKey to row.doseAmount,
                    )
                ),
            )
        }
    }
}

private val ReminderIdKey = ActionParameters.Key<Long>("reminderId")
private val MedicineIdKey = ActionParameters.Key<Long>("medicineId")
private val EpochMillisKey = ActionParameters.Key<Long>("epochMillis")
private val DoseAmountKey = ActionParameters.Key<Double>("doseAmount")

/** Same materialize-then-resolve sequence [com.hazia.emivita.core.scheduling.ReminderAlarmReceiver]
 *  uses for a fired alarm -- an upcoming occurrence shown in the widget may
 *  not have a DoseEvent row yet (it's still just a projection), so Take has
 *  to materialize it first before [com.hazia.emivita.core.data.repository.DoseEventRepository.resolve]
 *  has anything to resolve. */
class TakeNextDoseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val reminderId = parameters[ReminderIdKey] ?: return
        val medicineId = parameters[MedicineIdKey] ?: return
        val epochMillis = parameters[EpochMillisKey] ?: return
        val doseAmount = parameters[DoseAmountKey] ?: return

        val container = (context.applicationContext as EmivitaApplication).container
        takeFromWidget(context, container, reminderId, medicineId, epochMillis, doseAmount)
        NextRemindersWidget().update(context, glanceId)
    }
}

/** The widget's Take (§7 R113): a reminder that asks the amount (§7 R109)
 *  opens the amount screen on the materialised slot instead of recording the
 *  planned dose. The notification goes with the answer, in the repository. */
internal suspend fun takeFromWidget(context: Context, container: AppContainer, reminderId: Long, medicineId: Long, epochMillis: Long, doseAmount: Double) {
    val doseEventId = container.doseEventRepository.materializedId(reminderId, medicineId, epochMillis, doseAmount) ?: return
    if (container.reminderRepository.getById(reminderId)?.variableAmount == true) {
        context.startActivity(TakeAmountActivity.intent(context, doseEventId))
    } else {
        container.doseEventRepository.resolve(doseEventId, DoseStatus.TAKEN)
    }
}

class NextRemindersWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextRemindersWidget()
}
