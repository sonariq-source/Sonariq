package com.music.sonariq.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.sonariq.R
import com.music.sonariq.data.settings.AppSettings
import com.music.sonariq.data.settings.EqPresetLink
import com.music.sonariq.data.settings.EqualizerMode
import com.music.sonariq.data.settings.UserEqualizerPreset
import com.music.sonariq.playback.AudioOutputStatus
import com.music.sonariq.playback.DspEffectAvailability
import com.music.sonariq.playback.EqLayout
import com.music.sonariq.playback.EqualizerPreset
import com.music.sonariq.playback.dspEffectAvailability
import com.music.sonariq.ui.haptics.Haptic
import com.music.sonariq.ui.haptics.rememberHaptics
import com.music.sonariq.ui.theme.SonariqGreen
import com.music.sonariq.ui.theme.SonariqLime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The app's own equaliser, in the two shapes people have already been taught to
 * read one in.
 *
 * **Dynamic** is a tone pad: one puck over a dot grid, tilting the spectrum
 * left-to-right and shaping the middle of it bottom-to-top, with Broad and
 * Focused choosing how wide each move is. Two numbers and a puck, for the
 * majority of people who want it warmer or brighter and have no interest in
 * which decibel went where.
 *
 * **Manual** is the seven-band graphic equaliser, with a preset list over it.
 *
 * Both drive the same filters — see [EqLayout] — so the tab is a way of
 * describing a curve rather than a different piece of audio machinery, and
 * switching between them glides rather than clicks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sessionId by AppSettings.audioSessionId.collectAsStateWithLifecycle()
    val enabled by AppSettings.equalizerEnabled.collectAsStateWithLifecycle()
    val mode by AppSettings.equalizerMode.collectAsStateWithLifecycle()
    val toneX by AppSettings.equalizerToneX.collectAsStateWithLifecycle()
    val toneY by AppSettings.equalizerToneY.collectAsStateWithLifecycle()
    val focused by AppSettings.equalizerFocused.collectAsStateWithLifecycle()
    val balance by AppSettings.equalizerBalance.collectAsStateWithLifecycle()
    val bands by AppSettings.equalizerBands.collectAsStateWithLifecycle()
    val preset by AppSettings.equalizerPreset.collectAsStateWithLifecycle()
    val bass by AppSettings.equalizerBass.collectAsStateWithLifecycle()
    val treble by AppSettings.equalizerTreble.collectAsStateWithLifecycle()
    val userPresets by AppSettings.userEqualizerPresets.collectAsStateWithLifecycle()
    val activeUserPresetName by AppSettings.activeUserPresetName.collectAsStateWithLifecycle()
    val outputStatus by AudioOutputStatus.current.collectAsStateWithLifecycle()

    val haptics = rememberHaptics()
    var pickingPreset by remember { mutableStateOf(false) }
    var savingPreset by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp)) {
            Text("Shape your sound", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(5.dp))
            Text(stringResource(R.string.equalizer), style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(3.dp))
            Text("Fine-tune the details of your listening experience.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingsGroup(
            footer = stringResource(R.string.equalizer_footer),
            topSpacing = 8.dp,
        ) {
            SettingsRow(
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.equalizer),
                trailing = {
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            haptics.play(if (it) Haptic.ToggleOn else Haptic.ToggleOff)
                            AppSettings.setEqualizerEnabled(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = {
                    haptics.play(if (!enabled) Haptic.ToggleOn else Haptic.ToggleOff)
                    AppSettings.setEqualizerEnabled(!enabled)
                },
            )
        }

        // Off, the whole apparatus below reads as unavailable rather than
        // merely idle. Each control is told so itself rather than having its
        // touches swallowed by a layer above: the pad and the faders all claim
        // the gesture with `awaitFirstDown(requireUnconsumed = false)` — they
        // have to, or the scrolling page would steal every vertical drag — so
        // an ancestor's consumption never reaches them. Consuming on the
        // Initial pass to force it took the page's own scrolling down too.
        val settingsAlpha by animateFloatAsState(
            targetValue = if (enabled) 1f else 0.38f,
            animationSpec = tween(220),
            label = "equalizerEnabled",
        )
        Column(modifier = Modifier.graphicsLayer { alpha = settingsAlpha }) {
            Spacer(Modifier.height(18.dp))
            SegmentedControl(
                options = EqualizerMode.entries.map { it.localizedLabel() },
                selectedIndex = EqualizerMode.entries.indexOf(mode),
                onSelect = { AppSettings.setEqualizerMode(EqualizerMode.entries[it]) },
                enabled = enabled,
                modifier = Modifier.padding(horizontal = GROUP_INSET),
            )

            when (mode) {
                EqualizerMode.DYNAMIC -> {
                    SettingsGroup(
                        header = stringResource(R.string.equalizer_tone),
                        footer = stringResource(
                            if (focused) R.string.equalizer_focused_footer else R.string.equalizer_broad_footer,
                        ),
                    ) {
                        TonePad(
                            x = toneX,
                            y = toneY,
                            onChange = { newX, newY -> AppSettings.setEqualizerTone(newX, newY) },
                            enabled = enabled,
                        )
                        ToneReadout(x = toneX, y = toneY)
                        RowDivider()
                        ChoiceRow(
                            label = stringResource(R.string.equalizer_broad),
                            selected = !focused,
                            onClick = { AppSettings.setEqualizerFocused(false) },
                            enabled = enabled,
                        )
                        RowDivider()
                        ChoiceRow(
                            label = stringResource(R.string.equalizer_focused),
                            selected = focused,
                            onClick = { AppSettings.setEqualizerFocused(true) },
                            enabled = enabled,
                        )
                        RowDivider()
                        // The tone pad's own undo: back to dead centre.
                        DestructiveRow(
                            label = stringResource(R.string.equalizer_reset),
                            enabled = enabled,
                        ) {
                            haptics.play(Haptic.Select)
                            AppSettings.setEqualizerTone(0, 0)
                        }
                    }
                }

                EqualizerMode.MANUAL -> {
                    SettingsGroup {
                        SettingsRow(
                            icon = Icons.Rounded.GraphicEq,
                            title = stringResource(R.string.equalizer_preset),
                            // A saved curve names itself here the way a fixed
                            // preset does — see AppSettings.activeUserPresetName.
                            value = activeUserPresetName ?: preset.localizedLabel(),
                            onClick = { pickingPreset = true },
                            enabled = enabled,
                        )
                    }
                    // The preset quick-switch: chips over the saved and
                    // built-in presets, wired to the same preset machinery as
                    // the sheet. The row above still opens the sheet, which is
                    // where presets get saved and deleted.
                    PresetChips(
                        selected = preset,
                        userPresets = userPresets,
                        activeUserPresetName = activeUserPresetName,
                        enabled = enabled,
                        onSelect = { AppSettings.setEqualizerPreset(it) },
                        onSelectUserPreset = { AppSettings.applyUserEqualizerPreset(it) },
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    SettingsGroup(
                        header = stringResource(R.string.equalizer_bands),
                        footer = stringResource(R.string.equalizer_bands_footer),
                    ) {
                        BandSliders(
                            bands = bands,
                            onChange = { AppSettings.setEqualizerBands(it) },
                            enabled = enabled,
                        )
                        RowDivider()
                        DestructiveRow(
                            label = stringResource(R.string.equalizer_reset),
                            enabled = enabled,
                        ) {
                            haptics.play(Haptic.Select)
                            AppSettings.setEqualizerPreset(EqualizerPreset.FLAT)
                        }
                    }
                }
            }

            // Bass and treble trim the low and high shelves of whichever tab is
            // driving, so they sit outside the tab switch rather than in one
            // of it. Their availability is the DSP chain's: on a
            // passthrough/offload bitstream there are no samples to shape, and
            // the group says so instead of leaving two faders that move
            // nothing.
            val toneAvailability = dspEffectAvailability(outputStatus)
            SettingsGroup(
                header = stringResource(R.string.equalizer_tone_controls),
                footer = stringResource(
                    if (toneAvailability == DspEffectAvailability.AVAILABLE) {
                        R.string.equalizer_tone_controls_footer
                    } else {
                        R.string.equalizer_tone_unavailable_footer
                    },
                ),
            ) {
                ToneControls(
                    bass = bass,
                    treble = treble,
                    enabled = enabled,
                    onBassChange = { AppSettings.setEqualizerBass(it) },
                    onTrebleChange = { AppSettings.setEqualizerTreble(it) },
                )
            }

            SettingsGroup(
                header = stringResource(R.string.equalizer_balance),
                footer = stringResource(R.string.equalizer_balance_footer),
            ) {
                BalanceControl(
                    balance = balance,
                    onChange = { AppSettings.setEqualizerBalance(it) },
                    enabled = enabled,
                )
            }

            // A stream that never gets decoded to linear PCM — a passthrough or
            // offload bitstream — reaches the output as the bytes it arrived
            // as, and there is no sample for a filter to touch. Said plainly
            // here rather than left as a dead control, because the alternative
            // is a screen that responds to every touch and changes no sound.
            //
            // This tested `actualEncoding == ENCODING_PCM_FLOAT` for a long
            // while, which was true of the old pipeline and has been wrong
            // since the DSP chain moved upstream into [PrecisionAudioSink].
            // Float output does not affect the equaliser at all; it only drops
            // Media3's *own* processors, which is a claim about silence
            // skipping and belongs on that setting, not this screen.
            if (!outputStatus.dspAvailable) {
                SettingsGroup(footer = stringResource(R.string.equalizer_bitstream_footer)) {
                    SettingsRow(
                        icon = Icons.Rounded.Warning,
                        title = stringResource(R.string.equalizer_bitstream_title),
                        subtitle = stringResource(R.string.equalizer_bitstream_subtitle),
                    )
                }
            }

        }

        // Outside the dimmed block on purpose: the device's own panel works
        // whether or not ours is switched on, and greying out a route to
        // somebody else's equaliser would be claiming ground we do not hold.
        SettingsGroup(footer = stringResource(R.string.system_equalizer_footer)) {
            SettingsRow(
                icon = Icons.Rounded.GraphicEq,
                title = stringResource(R.string.system_equalizer),
                subtitle = stringResource(R.string.system_equalizer_subtitle),
                onClick = { openEqualizer(context, sessionId) },
            )
        }

        Spacer(Modifier.height(32.dp))
    }

    if (pickingPreset) {
        ModalBottomSheet(
            onDismissRequest = { pickingPreset = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            PresetSheet(
                selected = preset,
                userPresets = userPresets,
                activeUserPresetName = activeUserPresetName,
                onSelect = {
                    AppSettings.setEqualizerPreset(it)
                    pickingPreset = false
                },
                onSelectUserPreset = {
                    AppSettings.applyUserEqualizerPreset(it)
                    pickingPreset = false
                },
                onDeleteUserPreset = { AppSettings.deleteUserEqualizerPreset(it.name) },
                onSavePreset = {
                    presetName = ""
                    savingPreset = true
                },
            )
        }
    }

    // Naming the current curve. The dialog floats over the sheet; saving
    // closes both and lands back on the bands with the new name on the
    // preset row. A blank name cannot be saved — the button stays disabled
    // rather than the tap failing silently.
    if (savingPreset) {
        AlertDialog(
            onDismissRequest = { savingPreset = false },
            title = { Text(stringResource(R.string.equalizer_save_preset_title)) },
            text = {
                TextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text(stringResource(R.string.equalizer_preset_name_hint)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (AppSettings.saveUserEqualizerPreset(presetName, bands)) {
                            haptics.play(Haptic.Select)
                            savingPreset = false
                            pickingPreset = false
                        }
                    },
                    enabled = presetName.isNotBlank(),
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { savingPreset = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

// ---- Tone pad --------------------------------------------------------------

/**
 * The dot grid and its puck.
 *
 * Drawn rather than composed: eleven by eleven is 121 dots, and 121 layout
 * nodes re-measured on every frame of a drag is a stutter on the exact gesture
 * that has to feel direct. One [Canvas] redraws them for the cost of the
 * circles themselves.
 *
 * The puck snaps to whole steps as the finger moves, with a tick at each one,
 * and springs between them rather than teleporting — a control that jumps
 * between dots reads as the touch being dropped, and the spring is what says it
 * was caught.
 */
@Composable
private fun TonePad(
    x: Int,
    y: Int,
    enabled: Boolean,
    onChange: (Int, Int) -> Unit,
) {
    val haptics = rememberHaptics()
    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
    val puckColor = MaterialTheme.colorScheme.background
    val shadowColor = Color.Black.copy(alpha = 0.16f)
    val steps = EqLayout.TONE_STEPS

    // The gesture block below is keyed on something that never changes, so it is
    // started once and captures whatever was in scope then. Read through these
    // and a second gesture begins from where the puck actually is, rather than
    // from where it was the first time this screen composed.
    val latestX by rememberUpdatedState(x)
    val latestY by rememberUpdatedState(y)

    // Spring, not tween: the puck has to keep up with a fast drag and still
    // settle rather than arrive and stop dead.
    val animatedX by animateFloatAsState(
        targetValue = x.toFloat(),
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessHigh),
        label = "tonePadX",
    )
    val animatedY by animateFloatAsState(
        targetValue = y.toFloat(),
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessHigh),
        label = "tonePadY",
    )

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ROW_INSET, vertical = 18.dp)
            .height(PAD_HEIGHT)
            .pointerInput(steps, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    // Claimed on the way down, before the scrolling parent gets
                    // to count this as the start of a fling. Everything in this
                    // screen scrolls; a pad that only took the gesture once it
                    // had moved would lose every vertical drag to the page.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var lastX = latestX
                    var lastY = latestY
                    fun report(offset: Offset) {
                        val (newX, newY) = stepAt(offset, size.toSize(), PUCK_RADIUS.toPx(), steps)
                        if (newX != lastX || newY != lastY) {
                            lastX = newX
                            lastY = newY
                            haptics.play(Haptic.Tick)
                            onChange(newX, newY)
                        }
                    }
                    report(down.position)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        if (change.positionChanged()) {
                            change.consume()
                            report(change.position)
                        }
                    }
                }
            },
    ) {
        val inset = PUCK_RADIUS.toPx()
        val usableWidth = size.width - inset * 2
        val usableHeight = size.height - inset * 2
        val columns = steps * 2
        val dotRadius = DOT_RADIUS.toPx()

        for (column in 0..columns) {
            for (row in 0..columns) {
                // The row and column through dead centre are drawn up, so the
                // origin is findable without a reading — the same job the
                // detent does on a balance slider.
                val onAxis = column == steps || row == steps
                drawCircle(
                    color = dotColor.copy(alpha = if (onAxis) 0.55f else 0.3f),
                    radius = dotRadius,
                    center = Offset(
                        inset + usableWidth * column / columns,
                        inset + usableHeight * row / columns,
                    ),
                )
            }
        }

        val puckCentre = Offset(
            inset + usableWidth * (animatedX + steps) / columns,
            inset + usableHeight * (steps - animatedY) / columns,
        )
        // Three soft rings instead of a blur: a real shadow pass on a view this
        // small costs more than the circles it would be shading.
        for (ring in 3 downTo 1) {
            drawCircle(
                color = shadowColor.copy(alpha = shadowColor.alpha / ring),
                radius = inset + ring * 2f,
                center = puckCentre.copy(y = puckCentre.y + ring),
            )
        }
        // Neon edge on the puck: a lime halo behind it and a hairline ring,
        // so the control reads as lit rather than merely light.
        drawCircle(
            color = SonariqLime.copy(alpha = 0.25f),
            radius = inset + 7f,
            center = puckCentre,
        )
        drawCircle(color = puckColor, radius = inset, center = puckCentre)
        drawCircle(
            color = SonariqLime,
            radius = inset,
            center = puckCentre,
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

/** Which step the finger is over, clamped to the grid. */
private fun stepAt(offset: Offset, size: Size, inset: Float, steps: Int): Pair<Int, Int> {
    val usableWidth = (size.width - inset * 2).coerceAtLeast(1f)
    val usableHeight = (size.height - inset * 2).coerceAtLeast(1f)
    val fractionX = ((offset.x - inset) / usableWidth).coerceIn(0f, 1f)
    val fractionY = ((offset.y - inset) / usableHeight).coerceIn(0f, 1f)
    val x = (fractionX * steps * 2).roundToInt() - steps
    // Screen coordinates grow downwards and the control does not: up is more.
    val y = steps - (fractionY * steps * 2).roundToInt()
    return x to y
}

/** The two numbers under the pad: how far it is tilted, and how far contoured. */
@Composable
private fun ToneReadout(x: Int, y: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Waves,
            contentDescription = stringResource(R.string.equalizer_tilt),
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = signed(x),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.width(22.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ShowChart,
            contentDescription = stringResource(R.string.equalizer_contour),
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = signed(y),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** Zero has no sign; anything else wears one, so the direction reads at a glance. */
private fun signed(value: Int): String = if (value > 0) "+$value" else value.toString()

/** A row that is one of a set, ticked when it is the one in force. */
@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val haptics = rememberHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) {
                if (!selected) {
                    haptics.play(Haptic.Select)
                    onClick()
                }
            }
            .padding(horizontal = ROW_INSET, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = stringResource(R.string.selected),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

// ---- Manual bands ----------------------------------------------------------

/**
 * Seven vertical faders, drawn.
 *
 * Material's `Slider` rotated a quarter turn keeps its horizontal touch
 * geometry and its horizontal thumb, which is why every Compose equaliser built
 * that way feels wrong under the finger. These are a track, a fill and a knob
 * on a canvas, taking the gesture on first touch the way [TonePad] does.
 *
 * The fill runs from the centre rather than the bottom because the value it is
 * showing is signed: a band at −6 dB and a band at +6 dB are equal and opposite,
 * and a bar growing from the floor would draw them as a short one and a tall
 * one.
 */
@Composable
private fun BandSliders(
    bands: List<Float>,
    enabled: Boolean,
    onChange: (List<Float>) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ROW_INSET - 4.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        EqLayout.MANUAL_BANDS_HZ.forEachIndexed { index, hz ->
            val value = bands.getOrElse(index) { 0f }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = formatGain(value),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (abs(value) < 0.05f) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    maxLines = 1,
                )
                Spacer(Modifier.height(8.dp))
                BandFader(
                    enabled = enabled,
                    value = value,
                    onChange = { updated ->
                        onChange(bands.toMutableList().also { it[index] = updated })
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = formatFrequency(hz),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun BandFader(
    value: Float,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    val haptics = rememberHaptics()
    val trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    // Neon fill: green melting into lime, the Sonariq accent gradient.
    val fillBrush = Brush.verticalGradient(listOf(SonariqGreen, SonariqLime))
    val knobColor = MaterialTheme.colorScheme.background
    val range = EqLayout.MANUAL_RANGE_DB
    // See [TonePad]: the gesture block outlives the composition that made it.
    val latest by rememberUpdatedState(value)

    Canvas(
        modifier = Modifier
            .width(FADER_WIDTH)
            .height(FADER_HEIGHT)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var last = latest
                    fun report(offset: Offset) {
                        val inset = KNOB_RADIUS.toPx()
                        val usable = (size.height - inset * 2).coerceAtLeast(1f)
                        val fraction = ((offset.y - inset) / usable).coerceIn(0f, 1f)
                        val raw = (1f - fraction * 2f) * range
                        val snapped = snapGain(raw, range)
                        if (abs(snapped - last) > 0.001f) {
                            // A tick per step, and the step at zero is the one
                            // people are aiming for when they undo a band.
                            haptics.play(Haptic.Tick)
                            last = snapped
                            onChange(snapped)
                        }
                    }
                    report(down.position)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        if (change.positionChanged()) {
                            change.consume()
                            report(change.position)
                        }
                    }
                }
            },
    ) {
        val inset = KNOB_RADIUS.toPx()
        val usable = size.height - inset * 2
        val centreY = inset + usable / 2
        val trackWidth = TRACK_WIDTH.toPx()
        val centreX = size.width / 2
        val knobY = inset + usable * (1f - (value / range + 1f) / 2f)

        drawRoundRect(
            color = trackColor,
            topLeft = Offset(centreX - trackWidth / 2, inset),
            size = Size(trackWidth, usable),
            cornerRadius = CornerRadius(trackWidth / 2),
        )
        if (abs(value) > 0.05f) {
            drawRoundRect(
                brush = fillBrush,
                topLeft = Offset(centreX - trackWidth / 2, minOf(centreY, knobY)),
                size = Size(trackWidth, abs(centreY - knobY)),
                cornerRadius = CornerRadius(trackWidth / 2),
            )
        }
        // The zero line, drawn over the track so it survives a full-scale fill.
        drawLine(
            color = trackColor.copy(alpha = 0.9f),
            start = Offset(centreX - trackWidth, centreY),
            end = Offset(centreX + trackWidth, centreY),
            strokeWidth = 1.dp.toPx(),
        )
        // Neon knob: a lime halo behind it and a hairline lime edge.
        drawCircle(
            color = SonariqLime.copy(alpha = 0.28f),
            radius = inset + 5f,
            center = Offset(centreX, knobY),
        )
        drawCircle(
            color = Color.Black.copy(alpha = 0.18f),
            radius = inset + 1.5f,
            center = Offset(centreX, knobY + 1.5f),
        )
        drawCircle(color = knobColor, radius = inset, center = Offset(centreX, knobY))
        drawCircle(
            color = SonariqLime,
            radius = inset,
            center = Offset(centreX, knobY),
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
}

/**
 * Half-decibel steps, with a wider one at zero.
 *
 * Flat is the value a band gets dragged back to far more often than any other,
 * and it is the one value a finger cannot hit reliably on a 150 dp travel — so
 * it is given a detent to fall into rather than a coordinate to find.
 */
private fun snapGain(raw: Float, range: Float): Float {
    if (abs(raw) < ZERO_DETENT_DB) return 0f
    return ((raw * 2f).roundToInt() / 2f).coerceIn(-range, range)
}

private fun formatGain(value: Float): String = when {
    abs(value) < 0.05f -> "0"
    value > 0 -> "+" + trimGain(value)
    else -> trimGain(value)
}

/** "+6" rather than "+6.0", but "+6.5" when the half matters. */
private fun trimGain(value: Float): String =
    if (abs(value - value.roundToInt()) < 0.05f) {
        value.roundToInt().toString()
    } else {
        String.format(Locale.getDefault(), "%.1f", value)
    }

/** "60", "2.5k", "14k" — the labels a hardware equaliser prints. */
private fun formatFrequency(hz: Float): String = when {
    hz < 1_000f -> hz.roundToInt().toString()
    hz % 1_000f == 0f -> "${(hz / 1_000f).roundToInt()}k"
    else -> String.format(Locale.getDefault(), "%.1fk", hz / 1_000f)
}

// ---- Bass & treble ---------------------------------------------------------

/**
 * The tone trims: two faders rather than a second slider kind. They are signed
 * trims like the bands, so they get the same control with the same zero
 * detent — [BandFader] is reused rather than re-taught, and its ±12 dB range
 * is the trims' range too.
 */
@Composable
private fun ToneControls(
    bass: Float,
    treble: Float,
    enabled: Boolean,
    onBassChange: (Float) -> Unit,
    onTrebleChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ROW_INSET - 4.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        ToneFader(
            label = stringResource(R.string.equalizer_bass),
            value = bass,
            enabled = enabled,
            onChange = onBassChange,
        )
        ToneFader(
            label = stringResource(R.string.equalizer_treble),
            value = treble,
            enabled = enabled,
            onChange = onTrebleChange,
        )
    }
}

@Composable
private fun ToneFader(
    label: String,
    value: Float,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // The readout wears its unit: a trim at "+6" with no unit reads as a
        // score, not a gain.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatGain(value),
                style = MaterialTheme.typography.labelSmall,
                color = if (abs(value) < 0.05f) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
                maxLines = 1,
            )
            Text(
                text = " dB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(8.dp))
        BandFader(
            value = value,
            enabled = enabled,
            onChange = onChange,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

// ---- Balance ---------------------------------------------------------------

/**
 * L and R with the trim between them, and a detent at centre for the same
 * reason [snapGain] has one: dead centre is where this spends its life, and it
 * is the one position a drag cannot land on by aim alone.
 */
@Composable
private fun BalanceControl(
    balance: Float,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    val haptics = rememberHaptics()
    val trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    val fillBrush = Brush.horizontalGradient(listOf(SonariqGreen, SonariqLime))
    val knobColor = MaterialTheme.colorScheme.background
    // See [TonePad]: the gesture block outlives the composition that made it.
    val latest by rememberUpdatedState(balance)

    Column(Modifier.padding(horizontal = ROW_INSET, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.equalizer_balance_left),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = String.format(Locale.getDefault(), "%.2f", balance),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.equalizer_balance_right),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(Modifier.height(10.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(KNOB_RADIUS * 2 + 8.dp)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var last = latest
                        fun report(offset: Offset) {
                            val inset = KNOB_RADIUS.toPx()
                            val usable = (size.width - inset * 2).coerceAtLeast(1f)
                            val fraction = ((offset.x - inset) / usable).coerceIn(0f, 1f)
                            val raw = fraction * 2f - 1f
                            val snapped = if (abs(raw) < BALANCE_DETENT) 0f else raw
                            if (abs(snapped - last) > 0.004f) {
                                if (snapped == 0f && last != 0f) haptics.play(Haptic.Tick)
                                last = snapped
                                onChange(snapped)
                            }
                        }
                        report(down.position)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            if (change.positionChanged()) {
                                change.consume()
                                report(change.position)
                            }
                        }
                    }
                },
        ) {
            val inset = KNOB_RADIUS.toPx()
            val usable = size.width - inset * 2
            val centreY = size.height / 2
            val centreX = inset + usable / 2
            val trackHeight = TRACK_WIDTH.toPx()
            val knobX = inset + usable * (balance + 1f) / 2f

            drawRoundRect(
                color = trackColor,
                topLeft = Offset(inset, centreY - trackHeight / 2),
                size = Size(usable, trackHeight),
                cornerRadius = CornerRadius(trackHeight / 2),
            )
            if (abs(balance) > 0.004f) {
                drawRoundRect(
                    brush = fillBrush,
                    topLeft = Offset(minOf(centreX, knobX), centreY - trackHeight / 2),
                    size = Size(abs(centreX - knobX), trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2),
                )
            }
            // Neon knob: a lime halo behind it and a hairline lime edge.
            drawCircle(
                color = SonariqLime.copy(alpha = 0.28f),
                radius = inset + 5f,
                center = Offset(knobX, centreY),
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.18f),
                radius = inset + 1.5f,
                center = Offset(knobX, centreY + 1.5f),
            )
            drawCircle(color = knobColor, radius = inset, center = Offset(knobX, centreY))
            drawCircle(
                color = SonariqLime,
                radius = inset,
                center = Offset(knobX, centreY),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
    }
}

// ---- Presets ---------------------------------------------------------------

/**
 * The preset quick-switch: one chip per saved curve and built-in preset,
 * scrolling horizontally above the bands. Fully functional — a tap applies
 * the curve through [AppSettings.setEqualizerPreset] /
 * [AppSettings.applyUserEqualizerPreset], the same calls the preset sheet
 * makes; the sheet itself stays for saving and deleting.
 */
@Composable
private fun PresetChips(
    selected: EqualizerPreset,
    userPresets: List<UserEqualizerPreset>,
    activeUserPresetName: String?,
    enabled: Boolean,
    onSelect: (EqualizerPreset) -> Unit,
    onSelectUserPreset: (UserEqualizerPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = GROUP_INSET, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Saved curves first, as in the sheet: they are the listener's own.
        items(userPresets, key = { "user:${it.name}" }) { userPreset ->
            PresetChip(
                label = userPreset.name,
                chosen = userPreset.name == activeUserPresetName,
                enabled = enabled,
                onClick = {
                    haptics.play(Haptic.Select)
                    onSelectUserPreset(userPreset)
                },
            )
        }
        // Custom is a state the bands can be in, not a curve anyone can
        // choose — see PresetSheet — so it gets no chip.
        items(
            items = EqualizerPreset.entries.filter { it != EqualizerPreset.CUSTOM },
            key = { "built-in:${it.name}" },
        ) { preset ->
            PresetChip(
                label = preset.localizedLabel(),
                chosen = preset == selected && activeUserPresetName == null,
                enabled = enabled,
                onClick = {
                    haptics.play(Haptic.Select)
                    onSelect(preset)
                },
            )
        }
    }
}

@Composable
private fun PresetChip(
    label: String,
    chosen: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val chipShape = RoundedCornerShape(20.dp)
    Box(
        modifier = Modifier
            // The chosen chip carries a soft lime glow — the neon treatment,
            // switched off the moment it isn't chosen.
            .shadow(
                if (chosen) 6.dp else 0.dp,
                chipShape,
                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            )
            .clip(chipShape)
            .background(
                if (chosen) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                },
            )
            .border(
                1.dp,
                if (chosen) {
                    Color.Transparent
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                },
                chipShape,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (chosen) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun PresetSheet(
    selected: EqualizerPreset,
    userPresets: List<UserEqualizerPreset>,
    activeUserPresetName: String?,
    onSelect: (EqualizerPreset) -> Unit,
    onSelectUserPreset: (UserEqualizerPreset) -> Unit,
    onDeleteUserPreset: (UserEqualizerPreset) -> Unit,
    onSavePreset: () -> Unit,
) {
    val haptics = rememberHaptics()
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            text = stringResource(R.string.equalizer_preset),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 14.dp),
        )
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outline,
        )
        // Sixteen curves is taller than the sheet on most phones, and laid
        // out straight the ones past the fold were simply unreachable. The
        // title and its rule stay put while the list underneath scrolls;
        // `fill = false` keeps a short list from stretching the sheet to the
        // full height it is allowed.
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        ) {
            // Saved curves first: they are the listener's own, so they outrank
            // the factory list. A tap applies, the bin deletes; the row's
            // click does not fire for the bin because the IconButton consumes
            // its own tap.
            if (userPresets.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.equalizer_your_presets),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 2.dp),
                )
                userPresets.forEach { userPreset ->
                    val chosen = userPreset.name == activeUserPresetName
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                haptics.play(Haptic.Select)
                                onSelectUserPreset(userPreset)
                            }
                            .padding(horizontal = 22.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = userPreset.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f),
                        )
                        if (chosen) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = stringResource(R.string.selected),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        IconButton(
                            onClick = {
                                haptics.play(Haptic.Select)
                                context.startActivity(EqPresetLink.shareIntent(userPreset))
                            },
                        ) {
                            Icon(
                                Icons.Rounded.Share,
                                contentDescription = stringResource(R.string.social_eq_share),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        IconButton(
                            onClick = {
                                haptics.play(Haptic.Select)
                                onDeleteUserPreset(userPreset)
                            },
                        ) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.delete),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        haptics.play(Haptic.Select)
                        onSavePreset()
                    }
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.equalizer_save_preset),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            EqualizerPreset.entries.forEach { preset ->
                // Custom is a state the bands can be in, not a curve anyone can
                // choose, so it is only ever shown when it is the one in
                // force — and a named user preset in force is not "Custom".
                if (preset == EqualizerPreset.CUSTOM &&
                    (selected != EqualizerPreset.CUSTOM || activeUserPresetName != null)
                ) {
                    return@forEach
                }
                val chosen = preset == selected && activeUserPresetName == null
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = preset != EqualizerPreset.CUSTOM) {
                            haptics.play(Haptic.Select)
                            onSelect(preset)
                        }
                        .padding(horizontal = 22.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = preset.localizedLabel(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    if (chosen) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = stringResource(R.string.selected),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EqualizerMode.localizedLabel(): String = stringResource(
    when (this) {
        EqualizerMode.DYNAMIC -> R.string.equalizer_dynamic
        EqualizerMode.MANUAL -> R.string.equalizer_manual
    },
)

@Composable
private fun EqualizerPreset.localizedLabel(): String = stringResource(
    when (this) {
        EqualizerPreset.FLAT -> R.string.eq_preset_flat
        EqualizerPreset.ACOUSTIC -> R.string.eq_preset_acoustic
        EqualizerPreset.BASS_BOOST -> R.string.eq_preset_bass_boost
        EqualizerPreset.BASS_CUT -> R.string.eq_preset_bass_cut
        EqualizerPreset.VOCAL -> R.string.eq_preset_vocal
        EqualizerPreset.TREBLE_BOOST -> R.string.eq_preset_treble_boost
        EqualizerPreset.TREBLE_CUT -> R.string.eq_preset_treble_cut
        EqualizerPreset.LOUDNESS -> R.string.eq_preset_loudness
        EqualizerPreset.SPOKEN_WORD -> R.string.eq_preset_spoken_word
        EqualizerPreset.ELECTRONIC -> R.string.eq_preset_electronic
        EqualizerPreset.ROCK -> R.string.eq_preset_rock
        EqualizerPreset.HIP_HOP -> R.string.eq_preset_hip_hop
        EqualizerPreset.JAZZ -> R.string.eq_preset_jazz
        EqualizerPreset.CLASSICAL -> R.string.eq_preset_classical
        EqualizerPreset.SMALL_SPEAKERS -> R.string.eq_preset_small_speakers
        EqualizerPreset.LATE_NIGHT -> R.string.eq_preset_late_night
        EqualizerPreset.CUSTOM -> R.string.eq_preset_custom
    },
)

private val PAD_HEIGHT = 230.dp
private val PUCK_RADIUS = 19.dp
private val DOT_RADIUS = 2.5.dp
private val FADER_HEIGHT = 150.dp
private val FADER_WIDTH = 34.dp
private val TRACK_WIDTH = 5.dp
private val KNOB_RADIUS = 10.dp

/** How close to flat a band has to be dragged before it snaps there. */
private const val ZERO_DETENT_DB = 0.6f

/** Same, for the balance trim's own centre. */
private const val BALANCE_DETENT = 0.04f
