package com.byd.extend.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Stable
internal data class UiPalette(
    val dark: Boolean,
    val background: Color,
    val surface: Color,
    val panel: Color,
    val panelAlt: Color,
    val field: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val muted: Color,
    val active: Color,
    val accent: Color,
    val green: Color,
    val greenSoft: Color,
    val yellow: Color,
    val yellowSoft: Color,
    val red: Color,
    val redSoft: Color,
    val disabled: Color,
)

internal val LocalPrimaryScroll = staticCompositionLocalOf<ScrollState> { error("Primary scroll is missing") }
internal val LocalCompactControls = staticCompositionLocalOf { false }

internal fun palette(theme: UiTheme) = if (theme == UiTheme.Dark) UiPalette(
    true, Color(0xFF080D12), Color(0xFF0E151D), Color(0xFF131B25), Color(0xFF172231),
    Color(0xFF18212C), Color(0xFF2B3847), Color(0xFF40536A), Color(0xFFF1F6FF),
    Color(0xFFAAB8CA), Color(0xFF173A5C), Color(0xFF2F86F6), Color(0xFF54D898),
    Color(0xFF123C2B), Color(0xFFF2C34E), Color(0xFF453817), Color(0xFFFF8C8C),
    Color(0xFF4C252A), Color(0xFF394453),
) else UiPalette(
    false, Color(0xFFEAF1F8), Color.White, Color.White, Color(0xFFF0F5FB), Color(0xFFF7FAFE),
    Color(0xFFC9D6E4), Color(0xFF6D7D8F), Color(0xFF121A23), Color(0xFF526274),
    Color(0xFFD9EAFE), Color(0xFF2F86F6), Color(0xFF36CF88), Color(0xFFD8F4E7),
    Color(0xFFF1C04C), Color(0xFFFFF1C9), Color(0xFFFF7C7C), Color(0xFFFFE1E1), Color(0xFFE1E7EF),
)

@Composable
internal fun ScreenSurface(
    colors: UiPalette,
    scroll: Boolean = true,
    compact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
        .border(1.dp, colors.border, RoundedCornerShape(8.dp)).background(colors.panel)
        .padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = if (compact) 4.dp else 14.dp)
    androidx.compose.runtime.CompositionLocalProvider(LocalCompactControls provides compact) {
        Column(if (scroll) base.verticalScroll(LocalPrimaryScroll.current) else base, content = content)
    }
}

@Composable
internal fun PageTitle(title: String, subtitle: String, colors: UiPalette, modifier: Modifier = Modifier) {
    Column(modifier.padding(bottom = 4.dp)) {
        Text(title, color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = colors.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun Panel(
    colors: UiPalette,
    modifier: Modifier = Modifier,
    padding: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, colors.border, RoundedCornerShape(8.dp))
        .background(colors.panel).padding(padding), content = content)
}

@Composable
internal fun Section(
    title: String,
    colors: UiPalette,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    bodyPadding: Dp = 14.dp,
    header: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val compact = LocalCompactControls.current
    val inset = if (compact && bodyPadding == 14.dp) 6.dp else bodyPadding
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, colors.border, RoundedCornerShape(8.dp))
        .background(colors.panel)) {
        if (header != null) header() else Row(
            Modifier.fillMaxWidth().background(colors.panelAlt)
                .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 6.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title.uppercase(), color = colors.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        Column(Modifier.fillMaxWidth().padding(inset),
            verticalArrangement = Arrangement.spacedBy(if (inset == 0.dp) 0.dp else if (compact) 4.dp else 10.dp),
            content = content)
    }
}

@Composable
internal fun StatusPill(state: StatusUiState, fallback: String, colors: UiPalette) {
    if (!state.visible) return
    val (foreground, background) = toneColors(state.tone, colors)
    Text(state.text.ifBlank { fallback }, color = foreground, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        maxLines = 1, modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(background)
            .padding(horizontal = 11.dp, vertical = 6.dp))
}

@Composable
internal fun StatusText(state: StatusUiState, colors: UiPalette, reserveLines: Boolean = false) {
    if (!state.visible && !reserveLines) return
    val (foreground, _) = toneColors(state.tone, colors)
    Text(if (state.visible) state.text else "", color = foreground, fontSize = 12.sp,
        minLines = if (reserveLines) 2 else 1, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

private fun toneColors(tone: StatusTone, colors: UiPalette) = when (tone) {
    StatusTone.Ok -> colors.green to colors.greenSoft
    StatusTone.Warning -> colors.yellow to colors.yellowSoft
    StatusTone.Error -> colors.red to colors.redSoft
    StatusTone.Neutral -> colors.muted to colors.disabled
}

internal data class PressFeedback(
    val interactionSource: MutableInteractionSource,
    val pressed: Boolean,
    val modifier: Modifier,
)

private const val VISUAL_PRESS_HOLD_MS = 90L

@Composable
internal fun rememberPressFeedback(
    enabled: Boolean = true,
    releaseHoldMillis: Long = VISUAL_PRESS_HOLD_MS,
): PressFeedback {
    val interactionSource = remember { MutableInteractionSource() }
    var visualPressed by remember { mutableStateOf(false) }
    LaunchedEffect(interactionSource) {
        val activePresses = mutableSetOf<PressInteraction.Press>()
        var releaseGeneration = 0
        visualPressed = false
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    activePresses += interaction
                    releaseGeneration++
                    visualPressed = true
                }
                is PressInteraction.Release -> {
                    activePresses -= interaction.press
                    if (activePresses.isEmpty()) {
                        val generation = ++releaseGeneration
                        if (releaseHoldMillis == 0L) {
                            visualPressed = false
                        } else {
                            visualPressed = true
                            launch {
                                delay(releaseHoldMillis)
                                if (generation == releaseGeneration) visualPressed = false
                            }
                        }
                    }
                }
                is PressInteraction.Cancel -> {
                    activePresses -= interaction.press
                    if (activePresses.isEmpty()) {
                        val generation = ++releaseGeneration
                        if (releaseHoldMillis == 0L) {
                            visualPressed = false
                        } else {
                            visualPressed = true
                            launch {
                                delay(releaseHoldMillis)
                                if (generation == releaseGeneration) visualPressed = false
                            }
                        }
                    }
                }
            }
        }
    }
    val scale by animateFloatAsState(
        targetValue = if (visualPressed) .97f else 1f,
        label = "pressScale",
    )
    return PressFeedback(interactionSource, visualPressed, Modifier.graphicsLayer {
        scaleX = scale
        scaleY = scale
    })
}

/** Keeps the visible press frame while actions dispatch immediately. */
@Composable
internal fun rememberVisualFirstClick(onClick: () -> Unit): () -> Unit {
    val latestOnClick by rememberUpdatedState(onClick)
    return remember {
        { latestOnClick() }
    }
}

internal fun pressBackground(base: Color, colors: UiPalette, pressed: Boolean): Color =
    if (pressed) colors.accent.copy(alpha = if (colors.dark) .24f else .14f) else base

@Composable
internal fun SwitchLine(
    title: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    colors: UiPalette,
    pending: Boolean = false,
    enabled: Boolean = true,
    strikeThrough: Boolean = false,
    compactSwitch: Boolean = true,
) {
    val compact = LocalCompactControls.current
    val press = rememberPressFeedback(enabled && !pending)
    Row(Modifier.fillMaxWidth()
        .background(pressBackground(Color.Transparent, colors, press.pressed))
        .then(press.modifier)
        .toggleable(value = checked, interactionSource = press.interactionSource, indication = null,
            enabled = enabled && !pending, role = Role.Switch,
            onValueChange = onCheckedChange)
        .padding(vertical = if (compact) 4.dp else 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.text, fontSize = if (compact) 14.sp else 16.sp, fontWeight = FontWeight.SemiBold,
                textDecoration = if (strikeThrough) TextDecoration.LineThrough else TextDecoration.None)
            if (hint.isNotBlank()) Text(hint, color = colors.muted, fontSize = 13.sp)
        }
        Spacer(Modifier.width(10.dp))
        AppSwitch(checked, onCheckedChange, colors, pending, compact = compactSwitch, enabled = enabled, clearSemantics = true)
    }
}

@Composable
internal fun AppSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    colors: UiPalette,
    pending: Boolean = false,
    compact: Boolean = false,
    enabled: Boolean = true,
    clearSemantics: Boolean = false,
    label: String? = null,
) {
    val width = if (compact) 42.dp else 56.dp
    val height = if (compact) 27.dp else 32.dp
    val knob = if (compact) 20.dp else 25.dp
    val knobOff = if (compact) 16.dp else 19.dp
    val knobPending = if (compact) 18.dp else 22.dp
    val press = rememberPressFeedback(enabled && !pending)
    val size by animateDpAsState(if (pending) knobPending else if (checked) knob else knobOff, tween(140), label = "switchSize")
    val offset by animateDpAsState(if (pending) (width - knobPending) / 2 else if (checked) width - knob - 3.dp else 3.dp,
        tween(140), label = "switchOffset")
    Box(Modifier.size(width, height).semantics { label?.let { contentDescription = it } }
        .clip(RoundedCornerShape(100.dp))
        .background(pressBackground(if (pending) colors.yellowSoft else if (checked) colors.accent else colors.disabled,
            colors, press.pressed))
        .then(press.modifier)
        .toggleable(value = checked, interactionSource = press.interactionSource, indication = null,
            enabled = enabled && !pending, role = Role.Switch,
            onValueChange = onCheckedChange)
        .then(if (clearSemantics) Modifier.clearAndSetSemantics { } else Modifier), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.offset(x = offset).size(size).clip(RoundedCornerShape(100.dp))
            .background(if (pending) colors.yellow else if (checked) Color(0xFFD9ECFF) else Color(0xFFD8E3EE)))
    }
}

@Composable
internal fun Segmented(
    items: List<String>,
    selected: Int,
    colors: UiPalette,
    modifier: Modifier = Modifier,
    indicatorPosition: Float? = null,
    enabled: (Int) -> Boolean = { true },
    onSelect: (Int) -> Unit,
) {
    val focus = LocalFocusManager.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val compact = LocalCompactControls.current
    BoxWithConstraints(modifier.height(if (compact) 38.dp else 42.dp).clip(RoundedCornerShape(22.dp))
        .border(1.dp, colors.borderStrong, RoundedCornerShape(22.dp)).background(colors.panelAlt)
        .padding(if (compact) 4.dp else 5.dp).selectableGroup()) {
        val segmentWidth = maxWidth / items.size
        val animatedIndicatorPosition by animateFloatAsState(
            selected.toFloat(), tween(durationMillis = 180, delayMillis = 0),
            label = "segmentSelectionPosition",
        )
        val visibleIndicatorPosition = indicatorPosition ?: animatedIndicatorPosition
        Box(Modifier.offset {
            IntOffset(with(density) { (segmentWidth * visibleIndicatorPosition).roundToPx() }, 0)
        }.width(segmentWidth).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(colors.accent))
        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { index, item ->
                val itemEnabled = enabled(index)
                val press = rememberPressFeedback(itemEnabled, releaseHoldMillis = 0L)
                val visualClick = rememberVisualFirstClick {
                    focus.clearFocus()
                    onSelect(index)
                }
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp))
                    .background(pressBackground(Color.Transparent, colors, press.pressed))
                    .then(press.modifier)
                    .selectable(selected = index == selected, interactionSource = press.interactionSource,
                        indication = null, enabled = itemEnabled, role = Role.Tab, onClick = visualClick),
                    contentAlignment = Alignment.Center) {
                    Text(item, color = if (!itemEnabled) colors.muted.copy(alpha = .4f)
                        else if (index == selected) Color.White else colors.muted,
                        fontSize = if (compact) 12.sp else 14.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

internal object DropdownPosition : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        val x = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left else anchorBounds.right - popupContentSize.width
        val below = anchorBounds.bottom
        val above = anchorBounds.top - popupContentSize.height
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        val y = when {
            below + popupContentSize.height <= windowSize.height -> below
            above >= 0 -> above
            else -> below.coerceIn(0, maxY)
        }
        return IntOffset(x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)), y)
    }
}

@Composable
internal fun ChoiceField(
    title: String,
    choices: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    colors: UiPalette,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val compact = LocalCompactControls.current
    val safeSelected = selected.coerceIn(0, choices.lastIndex.coerceAtLeast(0))
    val selectedBackground = colors.accent.copy(alpha = if (colors.dark) .20f else .04f)
    val selectedContent = if (colors.dark) Color.White else colors.text
    val fieldPress = rememberPressFeedback(enabled)
    val openMenu = rememberVisualFirstClick {
        focusManager.clearFocus()
        expanded = true
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = colors.text, fontSize = if (compact) 14.sp else 16.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 2, modifier = Modifier.weight(1f))
        BoxWithConstraints(Modifier.width(if (compact) 190.dp else 220.dp)) {
            val menuWidth = maxWidth
            Box(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(6.dp))
                .border(1.dp, colors.accent, RoundedCornerShape(6.dp))
                .background(pressBackground(selectedBackground, colors, fieldPress.pressed))
                .then(fieldPress.modifier)
                .clickable(interactionSource = fieldPress.interactionSource, indication = null,
                    enabled = enabled && choices.isNotEmpty(), role = Role.Button) {
                    openMenu()
                }.padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center) {
                Text(choices.getOrElse(safeSelected) { "—" }, color = selectedContent, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
                Icon(Icons.Outlined.ExpandMore, null, tint = selectedContent.copy(alpha = .78f),
                    modifier = Modifier.align(Alignment.CenterEnd).size(20.dp))
            }
            if (expanded) Popup(DropdownPosition, { expanded = false }, PopupProperties(focusable = true)) {
                Column(Modifier.width(menuWidth).clip(RoundedCornerShape(6.dp))
                    .border(1.dp, colors.borderStrong, RoundedCornerShape(6.dp)).background(colors.panel)) {
                    choices.forEachIndexed { index, option ->
                        val optionPress = rememberPressFeedback(enabled)
                        val choose = rememberVisualFirstClick {
                            val selectionPress = PressInteraction.Press(Offset.Zero)
                            fieldPress.interactionSource.tryEmit(selectionPress)
                            fieldPress.interactionSource.tryEmit(PressInteraction.Release(selectionPress))
                            onSelect(index)
                            expanded = false
                        }
                        Box(Modifier.fillMaxWidth().height(40.dp)
                            .background(pressBackground(if (index == safeSelected) selectedBackground else Color.Transparent,
                                colors, optionPress.pressed))
                            .then(optionPress.modifier)
                            .clickable(interactionSource = optionPress.interactionSource, indication = null,
                                enabled = enabled, role = Role.Button) { choose() },
                            contentAlignment = Alignment.Center) {
                            Text(option, color = if (index == safeSelected) selectedContent else colors.text,
                                fontSize = 14.sp,
                                fontWeight = if (index == safeSelected) FontWeight.SemiBold else FontWeight.Normal,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
                            if (index < choices.lastIndex) {
                                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp)
                                    .background(colors.border))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NumericSetting(
    title: String,
    value: String,
    suffix: String,
    colors: UiPalette,
    onCommit: (String) -> Unit,
    range: ClosedFloatingPointRange<Float> = 0f..9999f,
    enabled: Boolean = true,
    adjustable: Boolean = false,
    slider: Boolean = false,
    sliderDots: Boolean = false,
    inlineLabel: Boolean = false,
    showLabel: Boolean = true,
    compactSuffix: Boolean = false,
    beforeInput: (@Composable () -> Unit)? = null,
    afterInput: (@Composable () -> Unit)? = null,
    identity: Any = Unit,
    onPreview: (String, Long) -> String? = { value, _ -> value },
    onCommitSession: ((String, Long) -> Unit)? = null,
) {
    val compact = LocalCompactControls.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var draft by remember(identity, value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    var invalid by remember(identity, value) { mutableStateOf(false) }
    var sliderValue by remember(identity, value) {
        mutableStateOf((value.toFloatOrNull() ?: range.start).coerceIn(range))
    }
    var suppressBlurCommit by remember(identity) { mutableStateOf(false) }
    var lastPreview by remember(identity, value) { mutableStateOf<String?>(null) }
    val previewSession = remember(identity) { NumericPreviewSession() }
    var gestureSessionId by remember(identity) { mutableStateOf<Long?>(null) }
    var suppressNextSliderFinish by remember(identity) { mutableStateOf(false) }
    var activeDragStart by remember(identity) { mutableStateOf<DragInteraction.Start?>(null) }
    DisposableEffect(identity) {
        onDispose { previewSession.dispose() }
    }
    fun commit(raw: String = draft, sessionId: Long? = null) {
        val result = NumericDraftPolicy.resolve(raw, value, range)
        invalid = !result.valid
        draft = result.draft
        sliderValue = result.slider.coerceIn(range)
        suppressBlurCommit = true
        if (result.valid) {
            if (sessionId != null && onCommitSession != null) onCommitSession(raw, sessionId)
            else onCommit(raw)
        }
    }
    fun adjust(delta: Float) {
        val next = ((draft.toFloatOrNull() ?: range.start) + delta).coerceIn(range)
        draft = if (next % 1f == 0f) next.roundToInt().toString() else next.toString()
        invalid = false
        commit(draft)
    }
    fun finishSliderGesture() {
        // Material's slider may report both DragInteraction.Cancel and
        // onValueChangeFinished for one pointer sequence.  Make either callback the one
        // finalization point and suppress the paired callback; the next gesture clears this bit.
        if (suppressNextSliderFinish) {
            suppressNextSliderFinish = false
            return
        }
        activeDragStart = null
        val raw = sliderText(sliderValue)
        draft = raw
        val sessionId = gestureSessionId
        if (sessionId != null) {
            if (previewSession.finish(sessionId)) commit(raw, sessionId)
            gestureSessionId = null
        } else {
            commit(raw)
        }
        suppressNextSliderFinish = true
    }
    val sliderInteractionSource = remember(identity) { MutableInteractionSource() }
    val latestFinishSliderGesture by rememberUpdatedState(::finishSliderGesture)
    LaunchedEffect(sliderInteractionSource) {
        sliderInteractionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> activeDragStart = interaction
                is DragInteraction.Cancel -> if (activeDragStart === interaction.start) {
                    activeDragStart = null
                    latestFinishSliderGesture()
                }
                is DragInteraction.Stop -> if (activeDragStart === interaction.start) {
                    activeDragStart = null
                }
            }
        }
    }
    Row(if (inlineLabel) Modifier else Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 8.dp)) {
        if (showLabel) Text(title, color = colors.text, fontSize = if (compact) 14.sp else 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = if (slider) Modifier.width(if (compact) 60.dp else 200.dp)
                else if (inlineLabel) Modifier else Modifier.weight(1f), maxLines = 2)
        if (slider) {
            val sliderColors = SliderDefaults.colors(
                thumbColor = if (colors.dark) Color(0xFFD9ECFF) else Color.White,
                activeTrackColor = colors.accent, inactiveTrackColor = colors.borderStrong,
            )
            Slider(sliderValue, {
                val normalized = normalizeSliderValue(it, range)
                if (normalized != sliderValue) {
                    val sessionId = gestureSessionId ?: previewSession.begin().also {
                        // Dedupe only within one pointer gesture.  If the backend rejects a
                        // gesture and the canonical value is unchanged, a later gesture must
                        // still be allowed to retry the same normalized tick.
                        lastPreview = null
                        suppressNextSliderFinish = false
                        gestureSessionId = it
                    }
                    sliderValue = normalized
                    val raw = sliderText(normalized)
                    draft = raw
                    invalid = false
                    suppressBlurCommit = false
                    if (lastPreview != raw) {
                        lastPreview = raw
                        val accepted = onPreview(raw, sessionId)
                        // Preview callbacks are synchronous.  A null result is a backend
                        // rejection, so immediately restore both draft and slider to the
                        // canonical value instead of waiting for a recomposition that may never
                        // happen when the persisted value is unchanged.
                        val acceptedText = accepted ?: value
                        draft = acceptedText
                        sliderValue = (acceptedText.toFloatOrNull() ?: range.start).coerceIn(range)
                    }
                }
            }, valueRange = range, enabled = enabled,
                onValueChangeFinished = { finishSliderGesture() },
                interactionSource = sliderInteractionSource,
                steps = if (sliderDots) ((range.endInclusive - range.start).roundToInt() - 1).coerceAtLeast(0) else 0,
                colors = sliderColors, track = { state ->
                    if (sliderDots) SliderDefaults.Track(state, colors = sliderColors)
                    else SliderDefaults.Track(state, colors = sliderColors, drawStopIndicator = null)
                }, modifier = Modifier.weight(1f).height(32.dp))
        }
        beforeInput?.invoke()
        if (adjustable) NumberStep("−", title, colors, enabled) { adjust(-1f) }
        BasicTextField(draft, { text ->
            if (text.length <= 7 && text.matches(Regex("-?[0-9]*[.]?[0-9]*")) &&
                (range.start < 0 || !text.startsWith("-"))) {
                draft = text
                invalid = false
                suppressBlurCommit = false
            }
        }, enabled = enabled, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                commit(); focused = false; keyboard?.hide(); focusManager.clearFocus()
            }),
            cursorBrush = SolidColor(colors.accent), textStyle = TextStyle(colors.text, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
            modifier = Modifier.width(if (compact) 64.dp else 80.dp).height(if (compact) 36.dp else 44.dp)
                .onPreviewKeyEvent {
                    if (it.key == Key.Enter || it.key == Key.NumPadEnter) {
                        if (it.type == KeyEventType.KeyUp) {
                            commit(); focused = false; keyboard?.hide(); focusManager.clearFocus()
                        }
                        true
                    } else false
                }.onFocusChanged {
            if (focused && !it.isFocused && !suppressBlurCommit && draft != value) commit()
            if (!it.isFocused) suppressBlurCommit = false
            focused = it.isFocused
                }.clip(RoundedCornerShape(7.dp)).background(colors.field)
                .border(1.dp, if (invalid) colors.red else colors.borderStrong, RoundedCornerShape(7.dp))
                .semantics { contentDescription = title.replace('\n', ' ') }.padding(horizontal = 11.dp),
            decorationBox = { field -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { field() } })
        if (adjustable) NumberStep("+", title, colors, enabled) { adjust(1f) }
        Text(suffix, color = colors.muted, fontSize = if (suffix == "°") 22.sp else 12.sp,
            modifier = Modifier.then(if (compactSuffix || (compact && adjustable)) Modifier else Modifier.width(46.dp))
                .offset(y = if (suffix == "°") (-3).dp else 0.dp))
        afterInput?.invoke()
    }
}

/** Slider-backed production values are integer-normalized before preview/commit dispatch. */
internal fun normalizeSliderValue(value: Float, range: ClosedFloatingPointRange<Float>): Float =
    value.roundToInt().toFloat().coerceIn(range)

internal fun sliderText(value: Float): String = value.roundToInt().toString()

@Composable
private fun NumberStep(symbol: String, title: String, colors: UiPalette, enabled: Boolean, onClick: () -> Unit) {
    val press = rememberPressFeedback(enabled)
    val visualClick = rememberVisualFirstClick(onClick)
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, colors.borderStrong, RoundedCornerShape(6.dp))
        .background(pressBackground(if (enabled) colors.panelAlt else colors.disabled, colors, press.pressed))
        .then(press.modifier)
        .clickable(interactionSource = press.interactionSource, indication = null,
            enabled = enabled, role = Role.Button, onClick = visualClick)
        .semantics { contentDescription = "$title $symbol" }, contentAlignment = Alignment.Center) {
        Text(symbol, color = colors.text, fontSize = 20.sp)
    }
}

@Composable
internal fun ActionButton(
    text: String,
    colors: UiPalette,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    mainBackground: Boolean = false,
    height: Dp = 44.dp,
    maxLines: Int = 1,
    onClick: () -> Unit,
) {
    val press = rememberPressFeedback(enabled)
    val visualClick = rememberVisualFirstClick(onClick)
    val background = when {
        !enabled -> colors.disabled
        destructive -> colors.redSoft
        primary -> colors.accent.copy(alpha = if (colors.dark) .78f else .08f)
        mainBackground -> colors.background
        else -> colors.panelAlt
    }
    val foreground = when {
        !enabled -> colors.muted.copy(alpha = .62f)
        destructive -> colors.red
        primary && colors.dark -> Color.White
        else -> colors.text
    }
    Row(modifier.height(height).clip(RoundedCornerShape(7.dp))
        .border(1.dp, if (primary && enabled) colors.accent else colors.borderStrong, RoundedCornerShape(7.dp))
        .background(pressBackground(background, colors, press.pressed))
        .then(press.modifier)
        .clickable(interactionSource = press.interactionSource, indication = null,
            enabled = enabled, role = Role.Button, onClick = visualClick)
        .padding(horizontal = if (maxLines > 1) 4.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        icon?.let {
            Icon(it, null, tint = foreground, modifier = Modifier.size(18.dp))
            if (text.isNotEmpty()) Spacer(Modifier.width(7.dp))
        }
        if (text.isNotEmpty()) Text(text, color = foreground,
            fontSize = if (height < 40.dp || maxLines > 1) 12.sp else 14.sp,
            fontWeight = FontWeight.SemiBold, maxLines = maxLines, textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis)
    }
}

/** BYD HUD shutdown affordance: fixed square hit area and immediate dispatch. */
@Composable
internal fun ShutdownButton(
    contentDescription: String,
    colors: UiPalette,
    onClick: () -> Unit,
) {
    val press = rememberPressFeedback()
    val visualClick = rememberVisualFirstClick(onClick)
    val tint = colors.red
    val base = tint.copy(alpha = if (colors.dark) .20f else .12f)
    val pressed = tint.copy(alpha = if (colors.dark) .88f else .72f)
    Box(
        Modifier.size(42.dp).clip(RoundedCornerShape(7.dp))
            .border(1.dp, tint.copy(alpha = .85f), RoundedCornerShape(7.dp))
            .background(if (press.pressed) pressed else base)
            .then(press.modifier)
            .clickable(interactionSource = press.interactionSource, indication = null,
                role = Role.Button, onClick = visualClick)
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.PowerSettingsNew, contentDescription,
            tint = if (press.pressed) Color.White else tint, modifier = Modifier.size(28.dp))
    }
}

@Composable
internal fun Divider(colors: UiPalette) = Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))

@Composable
internal fun SettingsActionRow(
    title: String,
    hint: String,
    colors: UiPalette,
    verticalPadding: Dp = 12.dp,
    action: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = verticalPadding), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (hint.isNotBlank()) Text(hint, color = colors.muted, fontSize = 13.sp)
        }
        action()
    }
}
