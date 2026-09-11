package app.zemote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zemote.state.Account
import app.zemote.state.ConnectionState
import app.zemote.state.DeviceStatus
import app.zemote.ui.component.DeviceAvatar
import app.zemote.ui.component.StatusDot
import app.zemote.ui.component.statusPresentation

/** 底部弹窗：多设备切换 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSwitchSheet(
    accounts: List<Account>,
    activeId: String?,
    statuses: Map<String, DeviceStatus>,
    onSwitch: (Account) -> Unit,
    onDisconnect: (String) -> Unit,
    onAddDevice: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp),
        ) {
            Text(
                "切换设备",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )

            if (accounts.isEmpty()) {
                Text(
                    "暂无设备",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                accounts.forEach { account ->
                    val isActive = account.id == activeId
                    val deviceStatus = statuses[account.id] ?: DeviceStatus()
                    val presentation = statusPresentation(deviceStatus.state, deviceStatus.message)

                    Surface(
                        onClick = { onSwitch(account) },
                        color = if (isActive) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DeviceAvatar(id = account.id, iconSize = 20, corner = 12)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    account.label,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    StatusDot(
                                        color = presentation.color,
                                        pulsing = presentation.pulsing,
                                        size = 6,
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        presentation.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = presentation.color,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            when {
                                isActive -> Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = "当前设备",
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(20.dp),
                                )
                                deviceStatus.state == ConnectionState.CONNECTED -> IconButton(
                                    onClick = { onDisconnect(account.id) },
                                    modifier = Modifier.size(34.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.LinkOff,
                                        contentDescription = "断开",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(17.dp),
                                    )
                                }
                                deviceStatus.state == ConnectionState.CONNECTING -> StatusDot(
                                    color = MaterialTheme.colorScheme.tertiary,
                                    pulsing = true,
                                    size = 9,
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )

            Surface(
                onClick = { onAddDevice() },
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(30.dp)) {
                            Icon(
                                Icons.Rounded.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("添加新设备", style = MaterialTheme.typography.titleMedium)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}
