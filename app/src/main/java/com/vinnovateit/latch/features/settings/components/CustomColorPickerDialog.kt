package com.vinnovateit.latch.features.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private const val CustomColorSaturation = 0.75f
private const val CustomColorLightness = 0.45f

fun hslToColor(hue: Float, saturation: Float = CustomColorSaturation, lightness: Float = CustomColorLightness): Color {
  val c = (1f - kotlin.math.abs(2f * lightness - 1f)) * saturation
  val x = c * (1f - kotlin.math.abs((hue / 60f) % 2f - 1f))
  val m = lightness - c / 2f
  val (r1, g1, b1) = when {
    hue < 60f -> Triple(c, x, 0f)
    hue < 120f -> Triple(x, c, 0f)
    hue < 180f -> Triple(0f, c, x)
    hue < 240f -> Triple(0f, x, c)
    hue < 300f -> Triple(x, 0f, c)
    else -> Triple(c, 0f, x)
  }
  return Color(r1 + m, g1 + m, b1 + m)
}

fun colorToHue(color: Color): Float {
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

fun parseHexOrNull(value: String): Color? {
  return try {
    val hex = value.removePrefix("#")
    if (hex.length == 6) Color(0xFF000000.toInt() or hex.toInt(16)) else null
  } catch (_: Exception) {
    null
  }
}

fun Color.toHexString(): String {
  val r = (red * 255).roundToInt().coerceIn(0, 255)
  val g = (green * 255).roundToInt().coerceIn(0, 255)
  val b = (blue * 255).roundToInt().coerceIn(0, 255)
  return String.format("#%02X%02X%02X", r, g, b)
}

@Composable
fun CustomColorPickerDialog(
  initialColorHex: String,
  onDismiss: () -> Unit,
  onColorConfirmed: (String) -> Unit
) {
  var hexInput by remember { mutableStateOf(initialColorHex.uppercase()) }
  val initialColor = parseHexOrNull(initialColorHex) ?: Color(0xFFC01221)
  var currentColor by remember { mutableStateOf(initialColor) }
  var hue by remember { mutableFloatStateOf(colorToHue(initialColor)) }

  val formattedHex = if (hexInput.startsWith("#")) hexInput else "#$hexInput"
  val isValidHex = parseHexOrNull(formattedHex) != null

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = "Custom Accent Color",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
      )
    },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        // Preview Swatch
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(currentColor)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
          contentAlignment = Alignment.Center
        ) {
          val isLight = (0.299 * currentColor.red + 0.587 * currentColor.green + 0.114 * currentColor.blue) > 0.5
          Text(
            text = currentColor.toHexString(),
            color = if (isLight) Color.Black else Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
          )
        }

        Spacer(Modifier.height(16.dp))

        // Rainbow Hue Slider Track
        Text("Hue", style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.Start))
        val hueColors = remember {
          (0..360 step 30).map { hslToColor(it.toFloat()) }
        }
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Brush.horizontalGradient(hueColors))
        )

        Slider(
          value = hue,
          onValueChange = { newHue ->
            hue = newHue
            val newCol = hslToColor(newHue)
            currentColor = newCol
            hexInput = newCol.toHexString()
          },
          valueRange = 0f..360f,
          modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        // Hex Code Input
        OutlinedTextField(
          value = hexInput,
          onValueChange = { raw ->
            val clean = raw.trim().take(7)
            hexInput = clean
            val parsed = parseHexOrNull(if (clean.startsWith("#")) clean else "#$clean")
            if (parsed != null) {
              currentColor = parsed
              hue = colorToHue(parsed)
            }
          },
          label = { Text("Hex Code (#RRGGBB)") },
          singleLine = true,
          isError = !isValidHex,
          supportingText = {
            if (!isValidHex) Text("Enter valid 6-digit hex", color = MaterialTheme.colorScheme.error)
          },
          modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        // Quick Swatches Row
        Text("Presets", style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.Start))
        Spacer(Modifier.height(6.dp))
        val quickPresets = listOf(
          Color(0xFFC01221), // Red
          Color(0xFF2E7D32), // Green
          Color(0xFF1976D2), // Blue
          Color(0xFF7B1FA2), // Purple
          Color(0xFF3F51B5), // Indigo
          Color(0xFF00BCD4), // Cyan
          Color(0xFFFF9800), // Orange
          Color(0xFFE91E63)  // Pink
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          quickPresets.forEach { presetColor ->
            Box(
              modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(presetColor)
                .clickable {
                  currentColor = presetColor
                  hexInput = presetColor.toHexString()
                  hue = colorToHue(presetColor)
                }
            )
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          if (isValidHex) {
            onColorConfirmed(formattedHex)
          }
        },
        enabled = isValidHex
      ) {
        Text("Apply")
      }
    },
    dismissButton = {
      OutlinedButton(onClick = onDismiss) {
        Text("Cancel")
      }
    }
  )
}
