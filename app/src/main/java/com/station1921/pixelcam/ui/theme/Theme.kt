package com.station1921.pixelcam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Brand = Color(0xFF7C5CFF)
private val BrandDim = Color(0xFF4B37B8)
private val Accent = Color(0xFFFF6BA6)

private val DarkColors = darkColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = BrandDim,
    onPrimaryContainer = Color.White,
    secondary = Accent,
    onSecondary = Color.White,
    background = Color(0xFF0C0C10),
    onBackground = Color(0xFFF3F3F7),
    surface = Color(0xFF15151C),
    onSurface = Color(0xFFF3F3F7),
    surfaceVariant = Color(0xFF20202A),
    onSurfaceVariant = Color(0xFFADADBD),
    outline = Color(0xFF34343F),
    scrim = Color(0xCC000000)
)

/**
 * 修图应用固定使用暗色主题：
 * - Activity XML 主题 (Theme.Material) 本身是暗色，若 Compose 再跟随系统切浅色，
 *   两者不一致会出现白卡片/黑底黑字等杂色，且部分 OEM 强制暗色会进一步搅乱配色。
 * - 暗色环境也更适合修图时的颜色观察。
 */
@Composable
fun PixelCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography(),
        content = content
    )
}
