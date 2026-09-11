package app.zemote.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.zemote.state.ConnectionState
import app.zemote.ui.theme.StatusSuccess
import kotlin.math.abs

/** 呼吸脉冲状态点：连接中/已连接时持续呼吸，静止时恒定 */
@Composable
fun StatusDot(
    color: Color,
    pulsing: Boolean,
    modifier: Modifier = Modifier,
    size: Int = 8,
) {
    if (!pulsing) {
        Box(modifier.size(size.dp).background(color, CircleShape))
        return
    }
    val transition = rememberInfiniteTransition(label = "statusPulse")
    val scale by transition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulseScale",
    )
    val halo by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "pulseHalo",
    )
    Box(
        modifier = modifier.size((size * 2.4f).dp),
        contentAlignment = Alignment.Center,
    ) {
        // 向外扩散的光晕
        Box(
            modifier = Modifier
                .size((size * 2.4f).dp * halo.coerceAtLeast(0.1f))
                .background(color.copy(alpha = 0.35f * (1f - halo)), CircleShape)
        )
        Box(
            modifier = Modifier
                .size((size.dp) * scale)
                .background(color, CircleShape)
        )
    }
}

// 设备头像渐变盘：按设备 id 稳定散列，色相各不相同，一眼可辨
private val AvatarGradients = listOf(
    listOf(Color(0xFF7B5CFF), Color(0xFF5CC8FF)),
    listOf(Color(0xFFFF7AB6), Color(0xFFFFB86C)),
    listOf(Color(0xFF00C9A7), Color(0xFF40E0B0)),
    listOf(Color(0xFFFF6C6C), Color(0xFFFFA26B)),
    listOf(Color(0xFF4E8DF7), Color(0xFF7B5CFF)),
    listOf(Color(0xFF9C5CFF), Color(0xFFFF5CC8)),
)

private val DeviceGlyphs = listOf(
    Icons.Rounded.Devices,
    Icons.Rounded.Laptop,
    Icons.Rounded.Memory,
    Icons.Rounded.Router,
)

fun deviceGradient(id: String): List<Color> =
    AvatarGradients[abs(id.hashCode()) % AvatarGradients.size]

/** 设备渐变头像块：M3 Expressive 风格的圆角方 + 品牌渐变 + 设备图标 */
@Composable
fun DeviceAvatar(
    id: String,
    modifier: Modifier = Modifier,
    iconSize: Int = 24,
    corner: Int = 18,
) {
    val gradient = deviceGradient(id)
    Box(
        modifier = modifier
            .background(Brush.linearGradient(gradient), RoundedCornerShape(corner.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = DeviceGlyphs[abs(id.hashCode()) % DeviceGlyphs.size],
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(iconSize.dp),
        )
    }
}

/** 连接状态 → 文案、颜色、是否脉冲 */
data class StatusPresentation(val label: String, val color: Color, val pulsing: Boolean)

@Composable
fun statusPresentation(state: ConnectionState, message: String?): StatusPresentation {
    return when (state) {
        ConnectionState.CONNECTED -> StatusPresentation("已连接", StatusSuccess, true)
        ConnectionState.CONNECTING -> StatusPresentation(
            message?.takeIf { it.isNotBlank() } ?: "正在连接…",
            MaterialTheme.colorScheme.tertiary,
            true,
        )
        ConnectionState.ERROR -> StatusPresentation(
            message?.takeIf { it.isNotBlank() } ?: "连接失败",
            MaterialTheme.colorScheme.error,
            false,
        )
        ConnectionState.IDLE -> StatusPresentation("未连接", MaterialTheme.colorScheme.onSurfaceVariant, false)
    }
}

/** 工作区渐变头像（按 key 散列取色） */
@Composable
fun WorkspaceAvatar(key: String, modifier: Modifier = Modifier, icon: ImageVector) {
    val gradient = deviceGradient("ws:$key")
    Box(
        modifier = modifier.background(Brush.linearGradient(gradient), RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}
