package com.vinnovateit.latch.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vinnovateit.latch.ui.theme.AccentSeeds
import com.vinnovateit.latch.ui.theme.satoshiFontFamily

// ---------------------------------------------------------------------------
// Section wrapper
// ---------------------------------------------------------------------------

@Composable
internal fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = satoshiFontFamily(),
            )
        }
        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))) {
            content()
        }
    }
}

/** 3 dp gap between rows inside a [SettingsSection]. */
@Composable
internal fun SettingsRowGap() {
    Spacer(Modifier.height(3.dp))
}

// ---------------------------------------------------------------------------
// Individual row
// ---------------------------------------------------------------------------

@Composable
internal fun SettingsItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    val clickModifier = if (onClick != null && enabled) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(clickModifier),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
        ) {
            if (leadingIcon != null) {
                Box(
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .size(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = if (enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        },
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                    fontFamily = satoshiFontFamily(),
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (enabled) 1f else 0.38f,
                        ),
                        fontFamily = satoshiFontFamily(),
                    )
                }
            }

            if (trailingContent != null) {
                Box(
                    modifier = Modifier.padding(start = 16.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    trailingContent()
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Selection dialog
// ---------------------------------------------------------------------------

data class SelectionOption(
    val label: String,
    val icon: ImageVector? = null,
    val displayLabel: String = label,
)

@Composable
internal fun SettingsSelectionDialog(
    title: String,
    description: String? = null,
    options: List<SelectionOption>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    bottomContent: @Composable (() -> Unit)? = null,
) {
    LatchBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                fontFamily = satoshiFontFamily(),
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = satoshiFontFamily(),
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            options.forEach { option ->
                val isSelected = option.label == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            onSelect(option.label)
                            dismiss()
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (option.icon != null) {
                        Icon(
                            imageVector = option.icon,
                            contentDescription = null,
                            tint = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        text = option.displayLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontFamily = satoshiFontFamily(),
                        modifier = Modifier.weight(1f),
                    )
                    if (isSelected) {
                        Icon(
                            imageVector = LatchIcons.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            if (bottomContent != null) {
                Spacer(Modifier.height(12.dp))
                bottomContent()
            }
            Spacer(Modifier.height(12.dp))
            TextButton(
                onClick = { dismiss() },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Cancel", fontFamily = satoshiFontFamily())
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Destructive action dialog
// ---------------------------------------------------------------------------

@Composable
internal fun SettingsActionDialog(
    title: String,
    description: String,
    confirmText: String,
    cancelText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    LatchBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                fontFamily = satoshiFontFamily(),
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = satoshiFontFamily(),
                modifier = Modifier.padding(bottom = 16.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { dismiss() }) {
                    Text(cancelText, fontFamily = satoshiFontFamily())
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    onConfirm()
                    dismiss()
                }) {
                    Text(confirmText, color = MaterialTheme.colorScheme.error, fontFamily = satoshiFontFamily())
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Accent colour picker
// ---------------------------------------------------------------------------

@Composable
internal fun AccentColorPicker(
    selectedColorName: String,
    useMonochrome: Boolean,
    onColorSelected: (String) -> Unit,
    onMonochromeToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val customColor = AccentSeeds.parseHexOrNull(selectedColorName)
    var showInlineCustom by remember { mutableStateOf(customColor != null) }

    val initialCustomColor = customColor ?: AccentSeeds.forName(selectedColorName)
    var hue by remember(selectedColorName) { mutableFloatStateOf(colorToHue(initialCustomColor)) }
    var hexText by remember(selectedColorName) { mutableStateOf(with(AccentSeeds) { (customColor ?: initialCustomColor).toHexString() }) }
    var previewColor by remember(selectedColorName) { mutableStateOf(customColor ?: initialCustomColor) }
    var trackWidthPx by remember { mutableFloatStateOf(1f) }

    fun applyHue(fraction: Float) {
        hue = (fraction * 360f).coerceIn(0f, 360f)
        previewColor = hslToColor(hue, CustomColorSaturation, CustomColorLightness)
        hexText = with(AccentSeeds) { previewColor.toHexString() }
        onColorSelected(hexText)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Monochrome swatch — first in the row
            MonochromeSwatchButton(
                isSelected = useMonochrome,
                onClick = { onMonochromeToggle(!useMonochrome) },
            )

            AccentSeeds.ordered.forEach { (name, color) ->
                AccentSwatchButton(
                    color = color,
                    isSelected = name == selectedColorName && !showInlineCustom && !useMonochrome,
                    onClick = {
                        showInlineCustom = false
                        if (useMonochrome) onMonochromeToggle(false)
                        onColorSelected(name)
                    },
                )
            }

            Surface(
                onClick = {
                    if (useMonochrome) onMonochromeToggle(false)
                    showInlineCustom = !showInlineCustom
                    if (showInlineCustom) onColorSelected(hexText)
                },
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = if (showInlineCustom && customColor != null && !useMonochrome) customColor else MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(
                    if (showInlineCustom && !useMonochrome) 3.dp else 2.dp,
                    if (showInlineCustom && !useMonochrome) SolidColor(MaterialTheme.colorScheme.onSurface) else Brush.sweepGradient(RainbowSweep),
                ),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (showInlineCustom && customColor != null && !useMonochrome) {
                        Icon(
                            imageVector = LatchIcons.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    } else {
                        Icon(
                            imageVector = LatchIcons.Add,
                            contentDescription = "Custom colour",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }

        if (showInlineCustom && !useMonochrome) {
            Spacer(Modifier.height(14.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Compact borderless hex field
                Box(
                    modifier = Modifier
                        .width(80.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = hexText,
                        onValueChange = { typed ->
                            hexText = typed
                            AccentSeeds.parseHexOrNull(typed)?.let { parsed ->
                                previewColor = parsed
                                hue = colorToHue(parsed)
                                onColorSelected(typed)
                            }
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = satoshiFontFamily(),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Brush.horizontalGradient(FullHueGradient))
                        .onSizeChanged { trackWidthPx = it.width.toFloat().coerceAtLeast(1f) }
                        .pointerInput(Unit) {
                            detectTapGestures { offset -> applyHue(offset.x / trackWidthPx) }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                applyHue(change.position.x / trackWidthPx)
                            }
                        },
                ) {
                    val thumbFraction = (hue / 360f).coerceIn(0f, 1f)
                    Surface(
                        modifier = Modifier
                            .size(28.dp)
                            .offset {
                                IntOffset(
                                    (thumbFraction * (trackWidthPx - 28.dp.toPx())).toInt(),
                                    0,
                                )
                            },
                        shape = CircleShape,
                        color = Color.Transparent,
                        border = BorderStroke(3.dp, Color.White),
                    ) {}
                }
            }
        }
    }
}

@Composable
private fun AccentSwatchButton(
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        shape = CircleShape,
        color = color,
        border = BorderStroke(
            3.dp,
            if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
        ),
    ) {
        if (isSelected) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = LatchIcons.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** A half-black / half-white circle representing the monochrome scheme. */
@Composable
private fun MonochromeSwatchButton(isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        shape = CircleShape,
        color = Color.Unspecified,
        border = BorderStroke(
            3.dp,
            if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
        ),
    ) {
        Box(Modifier.size(44.dp)) {
            // Left half — black
            Box(
                Modifier
                    .size(44.dp)
                    .clip(androidx.compose.ui.graphics.RectangleShape)
                    .background(Color(0xFF212121)),
            )
            // Right half — white (overlaid)
            Box(
                Modifier
                    .size(22.dp, 44.dp)
                    .align(Alignment.CenterEnd)
                    .background(Color(0xFFF5F5F5)),
            )
            if (isSelected) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = LatchIcons.Check,
                        contentDescription = null,
                        tint = Color(0xFF9E9E9E),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** Sampled every 30deg of hue at the picker's fixed saturation/lightness. */
private val RainbowSweep: List<Color> =
    (0..360 step 30).map { hslToColor(it.toFloat(), CustomColorSaturation, CustomColorLightness) }

/** Fixed saturation/lightness for the hue slider -- matches the presets' depth. */
private const val CustomColorSaturation = 0.75f
private const val CustomColorLightness = 0.42f

private val FullHueGradient: List<Color> =
    (0..360 step 15).map { hslToColor(it.toFloat(), CustomColorSaturation, CustomColorLightness) }

/** Standard HSL -> RGB, hue in [0, 360), saturation/lightness in [0, 1]. */
private fun hslToColor(hue: Float, saturation: Float, lightness: Float): Color {
    val c = (1f - kotlin.math.abs(2f * lightness - 1f)) * saturation
    val x = c * (1f - kotlin.math.abs((hue / 60f) % 2f - 1f))
    val m = lightness - c / 2f
    val r1: Float
    val g1: Float
    val b1: Float
    when {
        hue < 60f -> { r1 = c; g1 = x; b1 = 0f }
        hue < 120f -> { r1 = x; g1 = c; b1 = 0f }
        hue < 180f -> { r1 = 0f; g1 = c; b1 = x }
        hue < 240f -> { r1 = 0f; g1 = x; b1 = c }
        hue < 300f -> { r1 = x; g1 = 0f; b1 = c }
        else -> { r1 = c; g1 = 0f; b1 = x }
    }
    return Color(r1 + m, g1 + m, b1 + m)
}

/** Approximate hue (0-360) of an arbitrary RGB colour, for seeding the slider. */
private fun colorToHue(color: Color): Float {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    if (delta == 0f) return 0f
    val hue = when (max) {
        r -> 60f * (((g - b) / delta) % 6f)
        g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    return if (hue < 0f) hue + 360f else hue
}

/**
 * 24 dp circular swatch used as a trailing decoration in the accent SettingsItem.
 * In monochrome mode it shows a grey fill.
 */
@Composable
internal fun AccentSwatch(accentColor: Color, useMonochrome: Boolean) {
    Surface(
        modifier = Modifier.size(24.dp),
        shape = CircleShape,
        color = if (useMonochrome) MaterialTheme.colorScheme.onSurfaceVariant else accentColor,
    ) {}
}
