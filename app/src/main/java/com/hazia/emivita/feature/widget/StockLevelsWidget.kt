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
import java.time.LocalDate
import androidx.glance.LocalContext
import com.hazia.emivita.R
import com.hazia.emivita.core.format.LocaleFormats
import com.hazia.emivita.core.format.formatDecimalLocalized

private const val MAX_ROWS = 4

private data class StockRow(
    val medicineId: Long,
    val medicineName: String,
    val quantity: Double,
    val unit: String,
    val runOutDate: LocalDate?,
    val refillQuantity: Double?,
)

/**
 * Home-screen widget for spec §9 #7, the stock-tracking half -- same
 * "reads the cache, doesn't simulate" discipline as [NextRemindersWidget].
 * [com.hazia.emivita.core.scheduling.ProjectionState.stockRunOutDates] is
 * already computed as a side effect of the cache's own recompute pass, so
 * this widget only needs to sort and label it.
 */
class StockLevelsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as EmivitaApplication).container
        val state = container.occurrenceProjectionCache.state.value

        val tracked = container.medicineRepository.getAllTracked()
        val rows = tracked
            .asSequence()
            // getAllTracked()'s query is `WHERE unit IS NOT NULL`, so this
            // never drops a row in practice -- but the invariant lives in
            // MedicineDao's SQL, not in the type, so don't assert it here.
            .mapNotNull { medicine ->
                StockRow(
                    medicineId = medicine.id,
                    medicineName = medicine.name,
                    quantity = medicine.stockInfo.quantity,
                    unit = medicine.stockInfo.unit ?: return@mapNotNull null,
                    runOutDate = state.stockRunOutDates[medicine.id],
                    refillQuantity = medicine.stockInfo.refillQuantity,
                )
            }
            // Soonest run-out first; untracked-for-run-out (null) sorts last.
            .sortedWith(compareBy(nullsLast()) { it.runOutDate })
            .take(MAX_ROWS)
            .toList()

        val palette = container.settingsRepository.widgetPalette()
        provideContent {
            WidgetTheme(palette) {
                StockLevelsContent(rows)
            }
        }
    }
}

@Composable
private fun StockLevelsContent(rows: List<StockRow>) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.background)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Text(
            LocalContext.current.getString(R.string.widget_stock_levels_header),
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onBackground),
        )
        Spacer(modifier = GlanceModifier.height(4.dp))
        if (rows.isEmpty()) {
            Text(
                LocalContext.current.getString(R.string.widget_no_tracked_stock),
                style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant),
            )
        } else {
            rows.forEachIndexed { index, row ->
                StockRowItem(row, showRefillAction = index == 0 && row.refillQuantity != null)
            }
        }
    }
}

@Composable
private fun StockRowItem(row: StockRow, showRefillAction: Boolean) {
    val context = LocalContext.current
    val subtitle = row.runOutDate?.let { context.getString(R.string.widget_runs_out, LocaleFormats.monthDay(it)) }
        ?: context.getString(R.string.widget_no_imminent_run_out)
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(row.medicineName, style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurface))
            Text(
                LocalContext.current.getString(
                    R.string.widget_stock_row,
                    "${formatDecimalLocalized(row.quantity)} ${row.unit}",
                    subtitle,
                ),
                style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
            )
        }
        if (showRefillAction) {
            Text(
                LocalContext.current.getString(R.string.action_refill),
                modifier = GlanceModifier
                    .background(GlanceTheme.colors.primary)
                    .cornerRadius(8.dp)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .clickable(
                        actionRunCallback<RefillStockAction>(
                            actionParametersOf(
                                StockMedicineIdKey to row.medicineId,
                                StockRefillAmountKey to (row.refillQuantity ?: 0.0),
                            )
                        )
                    ),
                style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onPrimary, fontWeight = FontWeight.Medium),
            )
        }
    }
}

private val StockMedicineIdKey = ActionParameters.Key<Long>("medicineId")
private val StockRefillAmountKey = ActionParameters.Key<Double>("refillAmount")

class RefillStockAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val medicineId = parameters[StockMedicineIdKey] ?: return
        val refillAmount = parameters[StockRefillAmountKey] ?: return
        if (refillAmount <= 0.0) return

        val container = (context.applicationContext as EmivitaApplication).container
        container.doseEventRepository.logRefill(medicineId, refillAmount, System.currentTimeMillis())
        StockLevelsWidget().update(context, glanceId)
    }
}

class StockLevelsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StockLevelsWidget()
}
