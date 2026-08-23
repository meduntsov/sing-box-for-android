package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.bg.health.BelkaUpdateUiState
import io.nekohasekai.sfa.bg.health.BelkaVpnUiState

@Composable
fun BelkaSmartStatusCard(
    vpnEnabled: Boolean,
    vpnState: BelkaVpnUiState,
    updateState: BelkaUpdateUiState,
    onUpdate: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Smart VPN",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = when {
                        !vpnEnabled -> "○ выключен"
                        vpnState.checking -> "🟡 проверка"
                        vpnState.degraded && vpnState.selectedTag != null -> "🟠 деградация"
                        vpnState.noEligible -> "🔴 нет кандидата"
                        vpnState.selectedTag != null -> "🟢 активен"
                        else -> "🟡 выбор сервера"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            val selected = vpnState.selectedTag?.let(vpnState.nodes::get)
            val details = when {
                !vpnEnabled -> "VPN отключён"
                selected != null -> buildString {
                    append("Telegram ")
                    append(selected.telegramMs?.let { "${it.toInt()} ms" } ?: "—")
                    append(" · Instagram ")
                    append(selected.instagramMs?.let { "${it.toInt()} ms" } ?: "—")
                    append(" · Web ${selected.webOk}/${selected.webTotal}")
                    append(" · ")
                    append(selected.speedMbps?.let { "%.1f Mbps".format(it) } ?: "speed —")
                }
                else -> vpnState.lastAction.ifBlank { "Ожидание первой проверки…" }
            }
            Text(
                text = details,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when {
                updateState.downloading -> {
                    Text(
                        text = updateState.message ?: "Скачивание обновления…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(
                        progress = {
                            (updateState.progress ?: 0).coerceIn(0, 100) / 100f
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                updateState.available -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = updateState.message ?: "Доступно обновление",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        FilledTonalButton(onClick = onUpdate) {
                            Text("Обновить")
                        }
                    }
                }

                updateState.checking -> {
                    Text(
                        text = "Проверка обновлений…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                !updateState.message.isNullOrBlank() -> {
                    Text(
                        text = updateState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
