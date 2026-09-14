package com.hazia.emivita.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.ui.icons.MedicineIcons
import com.hazia.emivita.ui.theme.medicineAccent
import com.hazia.emivita.ui.theme.onAccent

/**
 * The medicine's identity tile (§7 R76, the reference app's grammar): a
 * rounded square in the medicine's own colour with its icon in whichever
 * ink reads on it. The one saturated thing on a card, and the same tile on
 * Today, Medicines, the detail header and History, so it is learnt once.
 */
@Composable
fun MedicineTile(medicine: Medicine, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val accent = medicineAccent(medicine)
    Surface(shape = RoundedCornerShape(size / 4), color = accent, modifier = modifier.size(size)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(MedicineIcons.iconFor(medicine.iconKey), contentDescription = null, tint = onAccent(accent), modifier = Modifier.size(size * 0.55f))
        }
    }
}

/** What a state disc shows: the four Today states, in the reference app's
 *  glyphs -- a bell for due, a check for taken, a cross for skipped, a clock
 *  for a slot the alarm has not reached. */
enum class DiscState(val icon: ImageVector) {
    DUE(Icons.Filled.Notifications),
    TAKEN(Icons.Filled.Check),
    SKIPPED(Icons.Filled.Close),
    UPCOMING(Icons.Outlined.Schedule),
}

/**
 * The state disc (§7 R76): the palette's pale `primaryContainer` with the
 * state's glyph in `onPrimaryContainer`. The *icon* carries the state, the
 * disc never changes colour -- a missed dose is the same disc with a
 * different glyph, which is what keeps it readable under colour-vision
 * deficiency and keeps `attention` for glyphs beside text.
 */
@Composable
fun StateDisc(state: DiscState, modifier: Modifier = Modifier, size: Dp = 36.dp, contentDescription: String? = null) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = modifier.size(size)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(state.icon, contentDescription = contentDescription, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(size * 0.55f))
        }
    }
}
