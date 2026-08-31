package com.spine.wallpaper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight

/**
 * 自定义颜色调色盘对话框（HSV 模型）。
 * 三个滑条：色相 / 饱和度 / 明度，实时预览 + 十六进制显示。
 */
@Composable
fun ColorPickerDialog(
    initialColor: Color,
    onDismiss: () -> Unit,
    onConfirm: (Color) -> Unit
) {
    // Color -> HSV
    val hsv = remember {
        val out = FloatArray(3)
        android.graphics.Color.colorToHSV(initialColor.toArgb(), out)
        out
    }
    var hue by remember { mutableStateOf(hsv[0]) }
    var saturation by remember { mutableStateOf(hsv[1]) }
    var value by remember { mutableStateOf(hsv[2]) }

    fun hsvColor(h: Float, s: Float, v: Float): Color {
        return Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))
    }

    val currentColor = hsvColor(hue, saturation, value)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E293B),
        title = {
            Text("选择背景颜色", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 实时预览 + 十六进制
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(currentColor)
                            .border(2.dp, Color(0xFF6366F1), CircleShape)
                    )
                    Column {
                        Text("预览", color = Color(0xFF94A3B8), fontSize = 11.sp)
                        Text(
                            "#%06X".format(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)) and 0xFFFFFF),
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // 色相滑条（彩虹渐变）
                GradientSlider(
                    label = "色相",
                    value = hue,
                    valueRange = 0f..360f,
                    brush = Brush.horizontalGradient(
                        (0..360 step 30).map { h -> hsvColor(h.toFloat(), 1f, 1f) }
                    ),
                    onValueChange = { hue = it }
                )

                // 饱和度滑条（灰 -> 当前色相满饱和）
                GradientSlider(
                    label = "饱和度",
                    value = saturation,
                    valueRange = 0f..1f,
                    brush = Brush.horizontalGradient(
                        listOf(
                            hsvColor(hue, 0f, value),
                            hsvColor(hue, 1f, value)
                        )
                    ),
                    onValueChange = { saturation = it }
                )

                // 明度滑条（黑 -> 当前色相亮度）
                GradientSlider(
                    label = "明度",
                    value = value,
                    valueRange = 0f..1f,
                    brush = Brush.horizontalGradient(
                        listOf(
                            hsvColor(hue, saturation, 0f),
                            hsvColor(hue, saturation, 1f)
                        )
                    ),
                    onValueChange = { value = it }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(currentColor) }) {
                Text("确定", color = Color(0xFF818CF8), fontSize = 14.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = Color(0xFF94A3B8), fontSize = 14.sp)
            }
        }
    )
}

/**
 * 带渐变轨道的滑条：渐变背景 + 透明轨道的 Material3 Slider 叠加。
 */
@Composable
private fun GradientSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    brush: Brush,
    onValueChange: (Float) -> Unit
) {
    Column {
        Text(label, color = Color(0xFF94A3B8), fontSize = 12.sp)
        Box {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(brush)
            )
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent
                )
            )
        }
    }
}
