package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.bg.health.BelkaNodeStatus
import io.nekohasekai.sfa.bg.health.BelkaUpdateUiState
import io.nekohasekai.sfa.bg.health.BelkaVpnState
import io.nekohasekai.sfa.bg.health.BelkaVpnUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val SMART_SWITCH_TOLERANCE_MS = 150.0
private const val SMART_MIN_SPEED_MBPS = 8.0

@Composable
fun BelkaSmartStatusCard(
    vpnEnabled: Boolean,
    vpnState: BelkaVpnUiState,
    updateState: BelkaUpdateUiState,
    onUpdate: () -> Unit,
) {
    var showDetails by remember { mutableStateOf(false) }

    if (showDetails) {
        BelkaSmartDetailsDialog(
            vpnEnabled = vpnEnabled,
            vpnState = vpnState,
            onDismiss = { showDetails = false },
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDetails = true },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
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
                    append(selected.speedMbps?.let(::formatSpeed) ?: "speed —")
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
                        horizontalArrangement = Arrangement.SpaceBetween,
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

@Composable
private fun BelkaSmartDetailsDialog(
    vpnEnabled: Boolean,
    vpnState: BelkaVpnUiState,
    onDismiss: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val selected = vpnState.selectedTag?.let(vpnState.nodes::get)
    val orderedNodes = vpnState.nodes.values.sortedWith(
        compareBy<BelkaNodeStatus> {
            if (it.tag == vpnState.selectedTag) 0 else 1
        }.thenBy {
            when {
                it.eligible -> normalScore(it)
                fallbackEligible(it, now) -> 20_000.0 + fallbackScore(it)
                else -> 100_000.0
            }
        },
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Smart VPN")
                Text(
                    text = BelkaVpnState.countryLabel(vpnState.selectedTag),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DetailSection(
                    title = "Почему выбран этот сервер",
                    body = selectionReason(vpnEnabled, vpnState, now),
                )

                if (selected != null) {
                    DetailSection(
                        title = "Текущий сервер",
                        body = buildString {
                            append(BelkaVpnState.countryLabel(selected.tag))
                            append("\n")
                            append("Telegram: ${formatMs(selected.telegramMs)}")
                            append(" · Instagram: ${formatMs(selected.instagramMs)}")
                            append("\n")
                            append("Web: ${selected.webOk}/${selected.webTotal}")
                            selected.webMs?.let { append(" (${formatMs(it)})") }
                            append(" · Speed: ${selected.speedMbps?.let(::formatSpeed) ?: "—"}")
                            append("\n")
                            append("Score: ${formatScore(normalScore(selected))}")
                            append(" · status: ${nodeStatus(selected, vpnState.selectedTag, now)}")
                        },
                    )
                }

                DetailSection(
                    title = "Последний цикл",
                    body = buildString {
                        append(vpnState.lastAction.ifBlank { "Нет завершённого решения" })
                        append("\nПоследняя проверка: ${formatTime(vpnState.lastCheckAt)}")
                        append(" · следующая: ${formatTime(vpnState.nextCheckAt)}")
                        if (vpnState.checking) append("\nСейчас идёт новый health-check.")
                    },
                )

                Text(
                    text = "Серверы",
                    style = MaterialTheme.typography.titleSmall,
                )

                if (orderedNodes.isEmpty()) {
                    Text(
                        text = "Статистика появится после первого health-check.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    orderedNodes.forEach { node ->
                        NodeDetails(
                            node = node,
                            selectedTag = vpnState.selectedTag,
                            now = now,
                        )
                    }
                }

                DetailSection(
                    title = "Логика Smart VPN",
                    body = """
                        • Полный health-check — раз в 10 минут; speed-тест — раз в 30 минут.
                        • Telegram: настоящий MTProto, достаточно DC, оба транспорта abridged/intermediate, медиана ≤ 900 ms.
                        • Instagram API: доступен и ≤ 1500 ms.
                        • Web: должны отвечать минимум 2 из 3 проверок (Google / Cloudflare / OpenAI).
                        • Speed не является жёстким блокером, но влияет на рейтинг.
                        • Score: 40% Telegram + 20% Instagram + 25% Web + 15% штраф за скорость. Меньше — лучше.
                        • Здоровый текущий сервер не меняется ради мелкой разницы: нужен выигрыш больше 150 ms-equivalent.
                        • Если текущий сервер реально перестал проходить health-check — failover сразу, без tolerance.
                        • Два подряд полных transport-fail → сервер в quarantine на 15 минут.
                        • ChatGPT / Claude / Gemini предпочитают DE; если DE неработоспособен, используют общий Smart VPN fallback.
                    """.trimIndent(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        },
    )
}

@Composable
private fun DetailSection(
    title: String,
    body: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NodeDetails(
    node: BelkaNodeStatus,
    selectedTag: String?,
    now: Long,
) {
    val status = nodeStatus(node, selectedTag, now)
    val score = when {
        node.eligible -> "score ${formatScore(normalScore(node))}"
        fallbackEligible(node, now) -> "fallback ${formatScore(fallbackScore(node))}"
        else -> "не участвует в выборе"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "${BelkaVpnState.countryLabel(node.tag)} · $status",
            style = MaterialTheme.typography.labelLarge,
            color = if (node.tag == selectedTag) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Text(
            text = "TG ${formatMs(node.telegramMs)} ${okMark(node.telegramOk)} · " +
                "IG ${formatMs(node.instagramMs)} ${okMark(node.instagramOk)} · " +
                "Web ${node.webOk}/${node.webTotal} · " +
                "${node.speedMbps?.let(::formatSpeed) ?: "speed —"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = score,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun selectionReason(
    vpnEnabled: Boolean,
    state: BelkaVpnUiState,
    now: Long,
): String {
    if (!vpnEnabled) return "VPN выключен — выбор сервера сейчас не выполняется."
    if (state.checking) {
        return "Идёт очередной health-check всех серверов. До его завершения отображается последний выбранный маршрут."
    }

    val selected = state.selectedTag?.let(state.nodes::get)
        ?: return "Сервер ещё не выбран: ожидается первый завершённый health-check."

    if (state.noEligible) {
        return "Ни один сервер не прошёл даже минимальные условия fallback. Smart VPN не переключал маршрут и оставил последний выбранный сервер."
    }

    if (state.degraded) {
        val bestFallback = state.nodes.values
            .filter { fallbackEligible(it, now) }
            .minByOrNull(::fallbackScore)
        return if (bestFallback?.tag == selected.tag) {
            "Ни один сервер не прошёл полный health-check. ${selected.tag.uppercase(Locale.US)} выбран как лучший доступный fallback с минимальным fallback-score ${formatScore(fallbackScore(selected))}."
        } else {
            "Полный health-check не пройден, поэтому Smart VPN работает в degraded-режиме и удерживает доступный fallback."
        }
    }

    if (!selected.eligible) {
        return "Выбранный сервер уже не помечен как полностью здоровый. При следующем завершённом health-check Smart VPN выполнит failover на пригодный узел."
    }

    val best = state.nodes.values
        .filter { it.eligible && it.quarantineUntil <= now }
        .minByOrNull(::normalScore)
        ?: return "${selected.tag.uppercase(Locale.US)} — единственный сервер, прошедший полный health-check."

    if (best.tag == selected.tag) {
        return "${selected.tag.uppercase(Locale.US)} прошёл полный health-check и имеет лучший (минимальный) score ${formatScore(normalScore(selected))} среди здоровых серверов."
    }

    val selectedScore = normalScore(selected)
    val bestScore = normalScore(best)
    val delta = selectedScore - bestScore
    return if (delta <= SMART_SWITCH_TOLERANCE_MS) {
        "${selected.tag.uppercase(Locale.US)} остаётся выбранным из-за anti-flapping: ${best.tag.uppercase(Locale.US)} лучше только на ${formatScore(delta)} ms-equivalent, а для переключения нужен выигрыш > ${SMART_SWITCH_TOLERANCE_MS.toInt()} ms."
    } else {
        "Последнее решение выбрало ${selected.tag.uppercase(Locale.US)}. По текущей опубликованной статистике ${best.tag.uppercase(Locale.US)} уже лучше на ${formatScore(delta)} ms-equivalent; следующий завершённый цикл должен пересмотреть выбор."
    }
}

private fun nodeStatus(
    node: BelkaNodeStatus,
    selectedTag: String?,
    now: Long,
): String = when {
    node.quarantineUntil > now -> "⏸ quarantine до ${formatTime(node.quarantineUntil)}"
    node.tag == selectedTag -> "● выбран"
    node.eligible -> "✓ eligible"
    fallbackEligible(node, now) -> "△ fallback"
    else -> "✕ fail"
}

private fun fallbackEligible(node: BelkaNodeStatus, now: Long): Boolean {
    if (node.quarantineUntil > now) return false
    val telegramUsable =
        node.telegramOk && node.telegramMs != null && node.telegramMs <= 1500.0
    val secondaryConnectivity = node.instagramOk || node.webOk >= 1
    return telegramUsable && secondaryConnectivity
}

private fun normalScore(node: BelkaNodeStatus): Double {
    val tg = node.telegramMs ?: 10_000.0
    val ig = node.instagramMs ?: 10_000.0
    val web = node.webMs ?: 10_000.0
    val speedPenalty = when {
        node.speedMbps == null -> 800.0
        node.speedMbps < SMART_MIN_SPEED_MBPS ->
            (3000.0 / node.speedMbps.coerceAtLeast(0.1)) +
                (SMART_MIN_SPEED_MBPS - node.speedMbps) * 50.0
        else -> 3000.0 / node.speedMbps
    }
    return 0.40 * tg +
        0.20 * ig +
        0.25 * web +
        0.15 * speedPenalty
}

private fun fallbackScore(node: BelkaNodeStatus): Double {
    val tg = node.telegramMs ?: 3000.0
    val ig = node.instagramMs ?: 2500.0
    val web = node.webMs ?: 2500.0
    val missingWebPenalty = (2 - node.webOk).coerceAtLeast(0) * 600.0
    val speedPenalty = node.speedMbps
        ?.let { 3000.0 / it.coerceAtLeast(0.1) }
        ?: 800.0

    return 0.50 * tg +
        0.20 * ig +
        0.20 * (web + missingWebPenalty) +
        0.10 * speedPenalty
}

private fun okMark(ok: Boolean) = if (ok) "✓" else "✕"

private fun formatMs(value: Double?): String =
    value?.let { "${it.toInt()} ms" } ?: "—"

private fun formatSpeed(value: Double): String =
    String.format(Locale.US, "%.1f Mbps", value)

private fun formatScore(value: Double): String =
    String.format(Locale.US, "%.0f", value)

private fun formatTime(value: Long): String {
    if (value <= 0L) return "—"
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(value))
}
