package com.hazia.emivita.feature.settings

import com.hazia.emivita.navigation.NavLabel
import com.hazia.emivita.ui.components.AttentionCard
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hazia.emivita.core.di.LocalAppContainer
import com.hazia.emivita.core.data.settings.SettingsRepository
import com.hazia.emivita.ui.form.FieldPair
import com.hazia.emivita.ui.form.FormScaffold
import com.hazia.emivita.ui.components.rememberDirty
import androidx.compose.runtime.SideEffect
import com.hazia.emivita.ui.form.FormTimeField
import com.hazia.emivita.ui.form.WeekdayGrid
import com.hazia.emivita.ui.theme.MedPalette
import com.hazia.emivita.ui.theme.ThemeMode
import com.hazia.emivita.ui.theme.displayName
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalTime
import androidx.compose.ui.res.stringResource
import com.hazia.emivita.R
import androidx.compose.ui.text.intl.Locale
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import com.hazia.emivita.ui.theme.labelRes
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.RadioButton
import android.app.Activity
import com.hazia.emivita.core.data.settings.TimeFormat
import com.hazia.emivita.core.locale.AppLocale
import com.hazia.emivita.core.format.LocaleFormats
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.hazia.emivita.feature.permissions.fullScreenIntentSettings
import androidx.compose.ui.semantics.semantics
import com.hazia.emivita.core.data.settings.StartPage
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import com.hazia.emivita.core.data.settings.TodayLayout
import com.hazia.emivita.ui.components.CollapsibleSection
import com.hazia.emivita.ui.theme.EmivitaTheme
import androidx.annotation.StringRes

/** Weekend Mode (spec §3/§9 item 12) -- wires the already-existing Settings
 *  destination to the global preferences SchedulingEngine reads
 *  (applyWeekendMode): a day-of-week set + one push-to time window.
 *
 *  This used to say "applied uniformly to every reminder type, no
 *  per-reminder opt-out", which stopped being true at §7 R36: the global
 *  setting here is necessary but not sufficient, and each reminder opts in
 *  via Reminder.weekendModeOptIn. Third copy of that stale sentence found
 *  and corrected (§7 R58 fixed the first two). */
@Composable
fun WeekendModeScreen(settingsRepository: SettingsRepository, onBack: () -> Unit, onSaved: () -> Unit = onBack, onDirty: (Boolean) -> Unit = {}) {
    val schedulingCoordinator = LocalAppContainer.current.schedulingCoordinator
    var loaded by remember { mutableStateOf(false) }
    // Saveable and filled once (§7 R83 (a)), so a rotation keeps the edits.
    var filled by rememberSaveable { mutableStateOf(false) }
    var enabled by rememberSaveable { mutableStateOf(false) }
    var days by rememberSaveable { mutableStateOf(setOf<Int>()) }
    var startHour by rememberSaveable { mutableIntStateOf(9) }
    var startMinute by rememberSaveable { mutableIntStateOf(0) }
    var endHour by rememberSaveable { mutableIntStateOf(18) }
    var endMinute by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (filled) { loaded = true; return@LaunchedEffect }
        enabled = settingsRepository.weekendModeEnabled.first()
        days = settingsRepository.weekendDays.first()
        val start = settingsRepository.weekendStartTime.first()
        startHour = start.hour
        startMinute = start.minute
        val end = settingsRepository.weekendEndTime.first()
        endHour = end.hour
        endMinute = end.minute
        filled = true
        loaded = true
    }

    var badTimes by remember { mutableStateOf(setOf<Int>()) }
    // Every field here is saved by Salva, as before; the baseline is the stored settings once read,
    // and Annulla and Back ask when anything differs from them (§7 R125, §7 R126).
    val dirty = rememberDirty(filled, enabled, days, startHour, startMinute, endHour, endMinute)
    SideEffect { onDirty(dirty) }
    FormScaffold(
        title = stringResource(R.string.settings_detail_weekend_mode),
        saveLabel = stringResource(R.string.common_save),
        saveEnabled = loaded && badTimes.isEmpty(),
        onSave = {
            scope.launch {
                settingsRepository.setWeekendModeEnabled(enabled)
                settingsRepository.setWeekendDays(days)
                settingsRepository.setWeekendStartTime(LocalTime.of(startHour, startMinute))
                settingsRepository.setWeekendEndTime(LocalTime.of(endHour, endMinute))
                // Weekend mode is read by SchedulingEngine at compute time, but
                // nothing observes these DataStore keys: without this, already
                // armed alarms keep their old times and the projection cache
                // (which watches the medicines/reminders tables, not DataStore)
                // never recomputes, so a saved change did nothing until the next
                // reboot. Before onSaved(), not after -- dismissing the sheet
                // leaves the composition and cancels this scope mid-flight.
                // See docs/SPEC.md §7 R23.
                schedulingCoordinator.rescheduleAll()
                onSaved()
            }
        },
        onCancel = onBack,
        onBack = onBack,
        inSheet = true,
    ) {
        if (!loaded) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@FormScaffold
        }

        Row(
            // One switch node, not a clickable row plus a live Switch (§9 #19 Stage B).
            Modifier.fillMaxWidth().toggleable(value = enabled, role = Role.Switch) { enabled = it },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_detail_delay_reminders_on_chosen_days), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.settings_detail_pushes_reminders_that_fall_inside_the_window),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = null)
        }

        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.settings_detail_days), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        // The weekdays on the form's 7-column grid (§7 R124 C6), named by java.time in the locale.
        WeekdayGrid(days) { days = it }

        Spacer(Modifier.height(20.dp))
        val startLabel = stringResource(R.string.common_window_start)
        val endLabel = stringResource(R.string.common_window_end)
        FieldPair(
            listOf(startLabel, endLabel), iconDp = 48.dp,
            first = { m -> FormTimeField(startLabel, startHour, startMinute, m, onError = { bad -> badTimes = if (bad) badTimes + 0 else badTimes - 0 }) { h, mi -> startHour = h; startMinute = mi } },
            second = { m -> FormTimeField(endLabel, endHour, endMinute, m, onError = { bad -> badTimes = if (bad) badTimes + 1 else badTimes - 1 }) { h, mi -> endHour = h; endMinute = mi } },
        )
    }
}

/**
 * Shown only while the Alarm tier can't do what the row above promises:
 * without [NotificationManagerCompat.canUseFullScreenIntent],
 * `ReminderNotificationBuilder.configureAlarmPresentation` silently degrades to
 * a tap-to-open action. From Android 14 that permission is not granted at
 * install outside the calling/alarm categories and can be revoked any time, so
 * this card is the only visible sign and way back. Re-read on ON_RESUME,
 * because a special-access settings screen delivers no activity result.
 */
@Composable
internal fun FullScreenAlarmAccessCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(NotificationManagerCompat.from(context).canUseFullScreenIntent()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = NotificationManagerCompat.from(context).canUseFullScreenIntent()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (granted) return

    Spacer(Modifier.height(16.dp))
    AttentionCard(stringResource(R.string.settings_detail_alarm_reminders_can_t_take_over_the_screen)) {
        Text(
            stringResource(R.string.settings_detail_right_now_an_alarm_tier_reminder_arrives_as),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Button(onClick = { context.startActivity(fullScreenIntentSettings(context)) }) {
            Text(stringResource(R.string.settings_detail_allow_full_screen_alarms))
        }
    }
}

/** Appearance -- palette + theme mode. The first profile is created silently
 *  with whatever `SettingsRepository.palette`'s own default is (see
 *  `EmivitaApp.kt`, spec §9 item 1 follow-up -- no onboarding screen exists
 *  to have asked); this is the "change your mind" path the Settings row
 *  always promised. Own copy of the palette-grid layout (small enough not
 *  to be worth extracting into a shared composable). */
@Composable
fun AppearanceScreen(settingsRepository: SettingsRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // null until DataStore answers, so the cards never show the default as
    // chosen for a frame and then jump (the loaded gate R56 had, kept).
    val palette by produceState<MedPalette?>(initialValue = null) { settingsRepository.palette.collect { value = it } }
    val themeMode by produceState<ThemeMode?>(initialValue = null) { settingsRepository.themeMode.collect { value = it } }
    val todayLayout by produceState<TodayLayout?>(initialValue = null) { settingsRepository.todayLayout.collect { value = it } }
    val timeFormat by settingsRepository.timeFormat.collectAsState(initial = TimeFormat.SYSTEM)
    var languageTag by remember { mutableStateOf(AppLocale.current(context)) }
    var themeOpen by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        DetailHeader(stringResource(R.string.common_appearance), onBack)

        val currentPalette = palette
        val currentMode = themeMode
        val currentLayout = todayLayout
        if (currentPalette == null || currentMode == null || currentLayout == null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }

        // Everything on this screen applies on the tap (§7 R76): the palette
        // and mode re-theme the app under the finger through EmivitaApp's
        // collected flows. The Save button that used to sit below the fold
        // -- the reason R75's pass saw nothing change -- is gone.
        CollapsibleSection(
            title = stringResource(R.string.appearance_theme_section),
            summary = stringResource(R.string.appearance_theme_summary, stringResource(currentMode.labelRes()), currentPalette.displayName()),
            expanded = themeOpen,
            onToggle = { themeOpen = !themeOpen },
        ) {
            Text(stringResource(R.string.settings_detail_theme_mode), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            // FlowRow, not Row (§9 #34): four chips do not fit a phone width in
            // Italian, and a Row answered by breaking "AMOLED" across three lines.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = currentMode == mode,
                        onClick = { scope.launch { settingsRepository.setThemeMode(mode) } },
                        label = { Text(stringResource(mode.labelRes()), maxLines = 1) },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.settings_detail_palette), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            OptionGrid(MedPalette.entries) { option ->
                PaletteCard(
                    palette = option,
                    themeMode = currentMode,
                    selected = option == currentPalette,
                    onClick = { scope.launch { settingsRepository.setPalette(option) } },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.appearance_layout_section), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        OptionGrid(TodayLayout.entries) { option ->
            LayoutCard(
                layout = option,
                selected = option == currentLayout,
                onClick = { scope.launch { settingsRepository.setTodayLayout(option) } },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(20.dp))
        // Open on (§9 #29, HM-33): applies at the next launch, saved on tap.
        val startPage by settingsRepository.startPage.collectAsState(initial = StartPage.TODAY)
        Text(stringResource(R.string.settings_start_page), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StartPage.entries.forEach { page ->
                FilterChip(
                    selected = startPage == page,
                    onClick = { scope.launch { settingsRepository.setStartPage(page) } },
                    // The tabs' own names, capped as the navigation bar caps them (§7 R129): at font 2.0 "Storico" broke (§7 R131).
                    label = { NavLabel(stringResource(when (page) { StartPage.TODAY -> R.string.nav_today; StartPage.MEDICINES -> R.string.nav_medicines; StartPage.HISTORY -> R.string.nav_history })) },
                )
            }
        }
        Text(
            stringResource(R.string.settings_start_page_desc),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))
        // Language and time format (§9 #28) apply on the spot like the rest.
        LanguageAndTimeSection(
            languageTag = languageTag,
            languages = AppLocale.supported(context),
            onLanguage = { tag ->
                languageTag = tag
                AppLocale.set(context, tag)
                if (AppLocale.needsRecreate) (context as? Activity)?.recreate()
            },
            timeFormat = timeFormat,
            onTimeFormat = { scope.launch { settingsRepository.setTimeFormat(it) } },
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** Two options per row, the last row padded so a lone card keeps its width. */
@Composable
private fun <T> OptionGrid(options: List<T>, card: @Composable RowScope.(T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.chunked(2).forEach { pair ->
            // A label that wraps makes its row's cards one height, the tallest (§7 R131).
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { card(it) }
                if (pair.size < 2) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** A language is named in itself ("Italiano", not "Italian"), the platform
 *  convention, so a person who cannot read the current language still finds
 *  theirs; the pseudolocales show their tags. */
internal fun languageDisplayName(tag: String?, systemLabel: String): String {
    if (tag == null) return systemLabel
    val locale = java.util.Locale.forLanguageTag(tag)
    val name = locale.getDisplayName(locale)
    return if (tag.contains("-X")) "$name ($tag)" else name.replaceFirstChar { it.titlecase(locale) }
}

@Composable
internal fun LanguageAndTimeSection(
    languageTag: String?,
    languages: List<String>,
    onLanguage: (String?) -> Unit,
    timeFormat: TimeFormat,
    onTimeFormat: (TimeFormat) -> Unit,
) {
    var pickingLanguage by remember { mutableStateOf(false) }
    val systemLabel = stringResource(R.string.settings_language_system)

    Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(4.dp))
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = stringResource(R.string.settings_language_change)) { pickingLanguage = true }.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(languageDisplayName(languageTag, systemLabel), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.settings_language_change), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }

    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.settings_time_format), style = MaterialTheme.typography.labelLarge)
    // A live example beside each option, so the choice is seen, not inferred.
    val example = remember { java.time.LocalDate.now().atTime(20, 5).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }
    TimeFormat.entries.forEach { option ->
        val label = when (option) {
            TimeFormat.SYSTEM -> stringResource(R.string.settings_time_format_system)
            TimeFormat.H12 -> stringResource(R.string.settings_time_format_12)
            TimeFormat.H24 -> stringResource(R.string.settings_time_format_24)
        }
        Row(
            Modifier.fillMaxWidth().selectable(selected = timeFormat == option, role = Role.RadioButton) { onTimeFormat(option) }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = timeFormat == option, onClick = null)
            Spacer(Modifier.width(8.dp))
            Text(label, modifier = Modifier.weight(1f))
            Text(
                exampleTime(option, example),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (pickingLanguage) {
        AlertDialog(
            onDismissRequest = { pickingLanguage = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = { LanguageList(languageTag, languages) { pickingLanguage = false; onLanguage(it) } },
            confirmButton = { TextButton(onClick = { pickingLanguage = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/** One radio node per language, "System default" first (§9 #28). */
@Composable
internal fun LanguageList(languageTag: String?, languages: List<String>, onLanguage: (String?) -> Unit) {
    val systemLabel = stringResource(R.string.settings_language_system)
    Column {
        (listOf<String?>(null) + languages).forEach { tag ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = languageTag == tag, role = Role.RadioButton) { onLanguage(tag) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = languageTag == tag, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(languageDisplayName(tag, systemLabel))
            }
        }
    }
}

/** The example rendered the way [option] would render it, without touching
 *  the process-wide hooks. */
internal fun exampleTime(option: TimeFormat, epochMillis: Long): String {
    val saved = LocaleFormats.hour24Override
    return try {
        LocaleFormats.hour24Override = when (option) { TimeFormat.SYSTEM -> null; TimeFormat.H12 -> false; TimeFormat.H24 -> true }
        LocaleFormats.time(epochMillis)
    } finally {
        LocaleFormats.hour24Override = saved
    }
}

/**
 * One palette, drawn in itself (§7 R76): the card sits inside its own
 * [EmivitaTheme], so its three swatches -- primary, attention, the ground --
 * and its name are that palette's, in the mode the screen is in. Until R76
 * every card showed one dot of its light primary and the selected card was
 * tinted in the *current* palette's green whatever it named.
 */
@Composable
internal fun PaletteCard(palette: MedPalette, themeMode: ThemeMode, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    EmivitaTheme(palette = palette, themeMode = themeMode) {
        OptionCard(
            selected = selected,
            onClick = onClick,
            label = palette.displayName(),
            modifier = modifier.semantics { contentDescription = palette.displayName() },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Swatch(MaterialTheme.colorScheme.primary)
                Swatch(EmivitaTheme.colors.attention)
                Swatch(MaterialTheme.colorScheme.surface, outlined = true)
            }
        }
    }
}

@Composable
private fun Swatch(color: Color, outlined: Boolean = false) {
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(color)
            .let { if (outlined) it.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape) else it },
    )
}

/** One Today layout (§9 #37): a 56 dp line-art of its shape in the palette's
 *  own colours, and its name. */
@Composable
internal fun LayoutCard(layout: TodayLayout, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(layout.labelRes())
    OptionCard(selected = selected, onClick = onClick, label = label, modifier = modifier.semantics { contentDescription = label }) {
        LayoutPreview(layout)
    }
}

@StringRes
internal fun TodayLayout.labelRes(): Int = when (this) {
    TodayLayout.TIMELINE -> R.string.today_layout_timeline
    TodayLayout.GROUPED -> R.string.today_layout_grouped
    TodayLayout.COMPACT -> R.string.today_layout_compact
    TodayLayout.HERO -> R.string.today_layout_hero
}

@Composable
private fun LayoutPreview(layout: TodayLayout) {
    val disc = MaterialTheme.colorScheme.primaryContainer
    val rail = MaterialTheme.colorScheme.primary
    val card = MaterialTheme.colorScheme.surfaceContainerHigh
    val line = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(56.dp)) {
        val w = size.width; val h = size.height
        val r = 5.dp.toPx()
        when (layout) {
            TodayLayout.TIMELINE -> {
                drawLine(rail, Offset(r + 2.dp.toPx(), 0f), Offset(r + 2.dp.toPx(), h), strokeWidth = 2.dp.toPx())
                for (i in 0 until 3) {
                    val y = h * (0.2f + 0.3f * i)
                    drawCircle(disc, r, Offset(r + 2.dp.toPx(), y))
                    drawRoundRect(card, Offset(r * 3.5f, y - r * 1.1f), Size(w - r * 3.5f, r * 2.2f), CornerRadius(r * 0.8f))
                }
            }
            TodayLayout.GROUPED -> {
                for (i in 0 until 3) {
                    val y = h * (0.12f + 0.3f * i)
                    drawCircle(rail, r * 0.35f, Offset(r * 0.5f, y))
                    drawRoundRect(line, Offset(r * 1.5f, y - r * 0.2f), Size(w * 0.3f, r * 0.4f), CornerRadius(r * 0.2f))
                    drawRoundRect(card, Offset(0f, y + r * 0.7f), Size(w, r * 1.6f), CornerRadius(r * 0.6f))
                    drawCircle(disc, r * 0.65f, Offset(r * 1.2f, y + r * 1.5f))
                }
            }
            TodayLayout.COMPACT -> {
                for (i in 0 until 4) {
                    val y = h * (0.12f + 0.25f * i)
                    drawCircle(disc, r * 0.7f, Offset(r, y))
                    drawRoundRect(line, Offset(r * 2.8f, y - r * 0.25f), Size(w * 0.6f, r * 0.5f), CornerRadius(r * 0.2f))
                    drawLine(line, Offset(0f, y + r * 1.4f), Offset(w, y + r * 1.4f), strokeWidth = 1.dp.toPx())
                }
            }
            TodayLayout.HERO -> {
                drawRoundRect(disc, Offset(0f, 0f), Size(w, h * 0.48f), CornerRadius(r))
                drawRoundRect(rail, Offset(r, h * 0.3f), Size(w * 0.3f, h * 0.12f), CornerRadius(r * 0.5f))
                for (i in 0 until 2) {
                    val y = h * (0.66f + 0.2f * i)
                    drawCircle(disc, r * 0.6f, Offset(r, y))
                    drawRoundRect(line, Offset(r * 2.6f, y - r * 0.25f), Size(w * 0.5f, r * 0.5f), CornerRadius(r * 0.2f))
                }
            }
        }
    }
}

/** The shared frame of a palette or layout card: tonal, a 2 dp border in
 *  primary when selected, the check at the top end, the name beneath. */
@Composable
private fun OptionCard(selected: Boolean, onClick: () -> Unit, label: String, modifier: Modifier = Modifier, preview: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        // Padding inside the Surface, not on its modifier: outside, the fill
        // was inset from the border and the label's first letter sat on the
        // clipped corner (seen on the phone, §7 R76).
        Box(Modifier.padding(12.dp)) {
            Column {
                preview()
                Spacer(Modifier.height(8.dp))
                // Wraps rather than ends in "…" (§7 R130's rule): at 360 dp "Prossima dose in evidenza" was cut at font 1.0 (§7 R131).
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = stringResource(R.string.settings_detail_selected),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
    }
}
