package com.hazia.emivita.feature.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.glance.LocalContext
import com.hazia.emivita.R
import com.hazia.emivita.core.format.LocaleFormats

private const val MAX_ROWS = 4

private data class NextReminderRow(
    val reminderId: Long,
    val medicineId: Long,
    val medicineName: String,
    val epochMillis: Long,
    val doseAmount: Double,
    /** §9 #31 (A-24): a follow-up projected off its parent's slot, shown as "≈". */
    val estimated: Boolean = false,
)

/**
 * Home-screen widget for spec §9 #7 -- reads [com.hazia.emivita.core.scheduling.OccurrenceProjectionCache]'s
 * already-computed [com.hazia.emivita.core.scheduling.ProjectionState.occurrences] rather than
 * running its own simulation, matching the cache's whole reason for existing
 * (Today/History/Widgets all sharing one projection pass -- see the cache's
 * own doc comment).
 *
 * Deliberately periodic refresh (`android:updatePeriodMillis`, see
 * next_reminders_widget_info.xml), not a live push from the cache -- wiring
 * the cache itself to know about widgets would put a feature-layer dependency
 * inside core/scheduling, and the Take action below already forces an
 * immediate refresh the moment someone actually interacts with the widget.
 */
class NextRemindersWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as EmivitaApplication).container
        val now = System.currentTimeMillis()
        val state = container.occurrenceProjectionCache.state.value

        // Plain List chain, not asSequence() -- Sequence's mapNotNull stores the
        // transform as a real function object rather than inlining it, which
        // can't call a suspend function (getById) the way List's inline
        // mapNotNull below can.
        val candidates = state.occurrences
            .filter { it.epochMillis >= now }
            .sortedBy { it.epochMillis }
            .distinctBy { it.reminderId } // one row per reminder -- its soonest occurrence
            .take(MAX_ROWS)

        val rows = candidates.mapNotNull { occurrence ->
            val medicine = container.medicineRepository.getById(occurrence.medicineId) ?: return@mapNotNull null
            NextReminderRow(
                reminderId = occurrence.reminderId,
                medicineId = occurrence.medicineId,
                medicineName = medicine.name,
                epochMillis = occurrence.epochMillis,
                doseAmount = occurrence.doseAmount,
                estimated = occurrence.estimated,
            )
        }

        provideContent {
            GlanceTheme {
                NextRemindersContent(rows)
            }
        }
    }
}

@Composable
private fun NextRemindersContent(rows: List<NextReminderRow>) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.background)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Text(
            LocalContext.current.getString(R.string.widget_next_doses_header),
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onBackground),
        )
        Spacer(modifier = GlanceModifier.height(4.dp))
        if (rows.isEmpty()) {
            Text(
                LocalContext.current.getString(R.string.widget_all_caught_up),
                style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant),
            )
        } else {
            rows.forEachIndexed { index, row ->
                NextReminderRowItem(row, showTakeAction = index == 0)
            }
        }
    }
}

@Composable
private fun NextReminderRowItem(row: NextReminderRow, showTakeAction: Boolean) {
    val time = LocaleFormats.time(row.epochMillis).let { if (row.estimated) "≈ $it" else it }
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(row.medicineName, style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurface))
            Text(time, style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant))
        }
        if (showTakeAction) {
            Text(
                LocalContext.current.getString(R.string.common_take),
                modifier = GlanceModifier
                    .background(GlanceTheme.colors.primary)
                    .cornerRadius(8.dp)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .clickable(
                        actionRunCallback<TakeNextDoseAction>(
                            actionParametersOf(
                                ReminderIdKey to row.reminderId,
                                MedicineIdKey to row.medicineId,
                                EpochMillisKey to row.epochMillis,
                                DoseAmountKey to row.doseAmount,
                            )
                        )
                    ),
                style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onPrimary, fontWeight = FontWeight.Medium),
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
        val doseEventId = container.doseEventRepository.materializeIfAbsent(reminderId, medicineId, epochMillis, doseAmount)
            ?: container.doseEventRepository.findByReminderAndTime(reminderId, epochMillis)?.id
        if (doseEventId != null) {
            container.doseEventRepository.resolve(doseEventId, DoseStatus.TAKEN)
        }
        NextRemindersWidget().update(context, glanceId)
    }
}

class NextRemindersWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextRemindersWidget()
}
