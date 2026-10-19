package com.hazia.emivita.ui.components

import com.hazia.emivita.ui.theme.NearBlack
import com.hazia.emivita.ui.theme.NearWhite
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.hazia.emivita.ui.theme.NeutralMedicineAccent
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import kotlin.math.max
import androidx.compose.ui.res.stringResource
import com.hazia.emivita.R

/** Parses a `#RRGGBB` string, or null if blank/invalid -- lenient about a
 *  missing leading '#' since users will type either. */
fun parseHexColor(hex: String): Color? {
    val cleaned = hex.trim().removePrefix("#")
    if (cleaned.length != 6) return null
    return runCatching { Color("#$cleaned".toColorInt()) }.getOrNull()
}

fun Color.toHexString(): String {
    val argb = AndroidColor.argb(
        (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt()
    )
    return String.format("#%06X", argb and 0xFFFFFF)
}

/**
 * The single medicine/profile accent-color picker (spec §3: "one HSV
 * saturation/value square + hue strip + hex-readout component, not a second
 * simpler swatch-based widget").
 *
 * Saturation and value come from a tap/drag position on a square canvas — x is
 * saturation, y is value, plain linear mapping, simpler to hit-test correctly
 * than a circular wheel while keeping the same HSV semantics. Hue comes from
 * the horizontal strip below, which the square is redrawn against, and a hex
 * text field is kept in sync both ways.
 */
@Composable
fun ColorPickerDialog(
    initialColor: Color?,
    onDismiss: () -> Unit,
    onConfirm: (Color) -> Unit,
    /**
     * Colours already assigned to the profile's other medicines, as
     * (name, colour). Drives the live differentiation hint spec §3 left open
     * and §7 R47 settled -- see ColorDistinguishability.kt for why the test is
     * perceptual distance rather than the contrast ratio §3 named. Empty by
     * default so the profile picker, which has nothing to collide with, need
     * not pass anything.
     */
    inUse: List<Pair<String, Color>> = emptyList(),
) {
    val hsv = remember {
        val initial = initialColor ?: NeutralMedicineAccent
        val out = FloatArray(3)
        AndroidColor.colorToHSV(
            AndroidColor.rgb((initial.red * 255).toInt(), (initial.green * 255).toInt(), (initial.blue * 255).toInt()),
            out,
        )
        out
    }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var saturation by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    var hexText by remember { mutableStateOf(Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value))).toHexString()) }
    var hexError by remember { mutableStateOf(false) }

    // Brightness stays between NearBlack's and NearWhite's (§7 R98): the square's
    // corners give near-black and near-white, never #000000 or #FFFFFF.
    fun currentColor() = Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value.coerceIn(PICKER_VALUE_MIN, PICKER_VALUE_MAX))))
    fun syncHexFromHsv() { hexText = currentColor().toHexString(); hexError = false }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.color_picker_choose_a_color)) },
        text = {
            Column {
                // Saturation/value square: x = saturation, y = brightness, both
                // at the current hue; hue itself comes from the strip below.
                val squareSize = 220.dp
                Box(
                    modifier = Modifier
                        .size(squareSize)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.hsv(hue, 1f, 1f))
                        .pointerInput(hue) {
                            fun update(offset: Offset) {
                                saturation = (offset.x / size.width).coerceIn(0f, 1f)
                                value = (1f - offset.y / size.height).coerceIn(0f, 1f)
                                syncHexFromHsv()
                            }
                            detectTapGestures { update(it) }
                        }
                        .pointerInput(hue) {
                            detectDragGestures { change, _ ->
                                saturation = (change.position.x / size.width).coerceIn(0f, 1f)
                                value = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                                syncHexFromHsv()
                            }
                        },
                ) {
                    // near-white-to-transparent (saturation) then transparent-to-near-black (value)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(squareSize)
                            .background(Brush.horizontalGradient(listOf(NearWhite, Color.Transparent))),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(squareSize)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, NearBlack))),
                    )
                    val markerX = (saturation * squareSize.value).dp
                    val markerY = ((1f - value) * squareSize.value).dp
                    Box(
                        Modifier
                            .padding(start = max(0f, markerX.value - 8f).dp, top = max(0f, markerY.value - 8f).dp)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Color.Transparent),
                    ) {
                        Canvas(Modifier.size(16.dp)) {
                            drawCircle(NearWhite, radius = 7.dp.toPx(), style = Stroke(width = 2.dp.toPx()))
                            drawCircle(NearBlack.copy(alpha = 0.4f), radius = 7.dp.toPx(), style = Stroke(width = 1.dp.toPx()))
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Hue strip -- full rainbow gradient, drag/tap sets hue directly.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.horizontalGradient(
                                (0..360 step 30).map { Color.hsv(it.toFloat().coerceAtMost(359.99f), 1f, 1f) }
                            )
                        )
                        .pointerInput(Unit) {
                            fun update(x: Float) {
                                hue = (x / size.width * 360f).coerceIn(0f, 359.99f)
                                syncHexFromHsv()
                            }
                            detectTapGestures { update(it.x) }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ -> hue = (change.position.x / size.width * 360f).coerceIn(0f, 359.99f); syncHexFromHsv() }
                        },
                )

                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(currentColor()),
                    )
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { typed ->
                            hexText = typed
                            val parsed = parseHexColor(typed)
                            if (parsed != null) {
                                val out = FloatArray(3)
                                AndroidColor.colorToHSV(
                                    AndroidColor.rgb((parsed.red * 255).toInt(), (parsed.green * 255).toInt(), (parsed.blue * 255).toInt()),
                                    out,
                                )
                                hue = out[0]; saturation = out[1]; value = out[2]
                                hexError = false
                            } else {
                                hexError = true
                            }
                        },
                        label = { Text(stringResource(R.string.color_picker_hex)) },
                        isError = hexError,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (hexError) {
                    Text(
                        stringResource(R.string.color_picker_not_a_valid_hex_color_e_g_4b7a6b),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                // Live differentiation hint (spec §3, settled in §7 R47).
                // Recomputed as the user drags rather than checked on Ok, so
                // it reads as guidance while choosing rather than a rejection
                // after choosing -- and it never disables "Use color": two
                // medicines the user wants in similar colours is a real case.
                val conflict = nearestColorConflict(currentColor(), inUse)
                if (conflict != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.color_picker_close_to, conflict.otherName),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(currentColor()) }, enabled = !hexError) { Text(stringResource(R.string.color_picker_use_color)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

private val PICKER_VALUE_MIN = maxOf(NearBlack.red, NearBlack.green, NearBlack.blue)
private val PICKER_VALUE_MAX = maxOf(NearWhite.red, NearWhite.green, NearWhite.blue)
