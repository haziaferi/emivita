package com.hazia.emivita.core.notification

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.settings.SettingsRepository
import com.hazia.emivita.ui.icons.MedicineIcons
import com.hazia.emivita.ui.theme.DEFAULT_PALETTE
import com.hazia.emivita.ui.theme.medicineAccent
import com.hazia.emivita.ui.theme.onAccent
import com.hazia.emivita.ui.theme.tokensFor
import kotlinx.coroutines.flow.first

/** The colour a notification's small icon and actions take: the saved
 *  palette's light-register primary (§7 R76). The shade shows it on its own
 *  ground, so the light register is the one that reads there in both modes. */
suspend fun notificationAccentArgb(settingsRepository: SettingsRepository): Int =
    tokensFor(settingsRepository.palette.first(), dark = false).primary.toArgb()

/** For the two group-summary builders, which are not suspending: the accent
 *  their last posted notification used, the default until one is posted. */
val DEFAULT_NOTIFICATION_ACCENT_ARGB: Int = tokensFor(DEFAULT_PALETTE, dark = false).primary.toArgb()

/** A reminder's colour (§7 R109, Appendix E B-43, medTimer as-is): the
 *  medicine's own, or [paletteAccent] when it has none or names are hidden,
 *  since a colour can tell a reader which medicine it is. */
fun reminderNotificationColour(medicine: Medicine, hideNames: Boolean, paletteAccent: Int): Int =
    if (hideNames || medicine.color == null) paletteAccent else medicineAccent(medicine.color).toArgb()

/** The large icon beside a reminder (§7 R109, B-43): the medicine's icon in
 *  its accent's ink on a disc of its colour, 64 dp square. */
fun medicineLargeIcon(context: Context, medicine: Medicine): Bitmap {
    val size = (64 * context.resources.displayMetrics.density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val accent = medicineAccent(medicine.color)
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent.toArgb() })
    val vector = MedicineIcons.iconFor(medicine.iconKey)
    val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = onAccent(accent).toArgb() }
    canvas.translate(size * 0.2f, size * 0.2f)
    canvas.scale(size * 0.6f / vector.viewportWidth, size * 0.6f / vector.viewportHeight)
    fun draw(group: VectorGroup) {
        for (node in group) when (node) {
            is VectorPath -> canvas.drawPath(node.pathData.toPath().asAndroidPath(), ink)
            is VectorGroup -> draw(node)
        }
    }
    draw(vector.root)
    return bitmap
}
