package com.hazia.emivita.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The one header the four tabs share (spec §9 #33, F-14; the shape §7 R59
 * found by eye and fixed on Medicines alone). A 16 dp-padded row of
 * headline text, and nothing in that row but the text: an action is an
 * `IconButton` with a 48 dp minimum target, and inside the row it stretches
 * the header by 16 dp on whichever tab has one -- Medicines sat 34 px low
 * for exactly this until R59, and History did until this file. Actions
 * float over the row's end in a Box, so they add no height; the layout test
 * measures a header with and without one and asks for the same number.
 */
@Composable
fun TabHeader(title: String, modifier: Modifier = Modifier, actions: (@Composable RowScope.() -> Unit)? = null) {
    Box(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
        }
        if (actions != null) {
            Row(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}
