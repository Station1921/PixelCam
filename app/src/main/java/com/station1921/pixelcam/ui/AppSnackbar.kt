package com.station1921.pixelcam.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 全应用统一的提示浮层。
 *
 * 样式固定为暗色圆角胶囊，跟应用主题一致：
 * M3 默认 Snackbar 用 colorScheme.inverseSurface，在暗色主题里那恰恰是近白色，
 * 所以要在这里显式指定暗底亮字。
 *
 * 注意：本宿主只作覆盖层叠加在页面上（由调用方用 Box + align 摆放），
 * 不参与 Column 布局测量——这样提示弹出时不会顶起/挤动页面任何区域。
 */
@Composable
fun AppSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        Snackbar(
            snackbarData = data,
            containerColor = Color(0xFF262630),
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(12.dp)
        )
    }
}
