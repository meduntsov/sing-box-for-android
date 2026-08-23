#!/usr/bin/env python3
from pathlib import Path


def patch_file(path_str: str, replacements: list[tuple[str, str, str]]) -> None:
    path = Path(path_str)
    text = path.read_text(encoding="utf-8")
    original = text
    for old, new, label in replacements:
        count = text.count(old)
        if count != 1:
            raise SystemExit(f"{path_str}: {label}: expected exactly 1 match, found {count}")
        text = text.replace(old, new, 1)
    if text == original:
        raise SystemExit(f"{path_str}: no changes made")
    path.write_text(text, encoding="utf-8")


patch_file(
    "app/src/main/java/io/nekohasekai/sfa/bg/health/HealthController.kt",
    [
        (
'''    private var cycleNumber = 0L
    private var lowInternetMode = false
''',
'''    private var cycleNumber = 0L
    private var lowInternetMode = false

    @Volatile
    private var manualLockedTag: String? = null

    @Volatile
    private var manualOperationInProgress = false
''',
            "manual state",
        ),
        (
'''    fun start() {
        if (loopJob != null) return
        Log.i(TAG, "v2.2 START selector=${plan.selectorTag}, nodes=${plan.nodes.joinToString(",") { it.tag }}")
''',
'''    fun start() {
        if (loopJob != null) return
        BelkaManualChecks.attach(this)
        Log.i(TAG, "v2.2 START selector=${plan.selectorTag}, nodes=${plan.nodes.joinToString(",") { it.tag }}")
''',
            "attach manual checks",
        ),
        (
'''    override fun close() {
        Log.i(TAG, "v2.2 STOP")
        loopJob?.cancel()
        loopJob = null
        scope.cancel()
    }
''',
'''    override fun close() {
        Log.i(TAG, "v2.2 STOP")
        BelkaManualChecks.detach(this)
        loopJob?.cancel()
        loopJob = null
        scope.cancel()
    }
''',
            "detach manual checks",
        ),
        (
'''        if (plan.nodes.isEmpty()) {
            Log.w(TAG, "cycle skipped: no concrete VLESS nodes")
            return
        }

        val cycle = ++cycleNumber
''',
'''        if (plan.nodes.isEmpty()) {
            Log.w(TAG, "cycle skipped: no concrete VLESS nodes")
            return
        }

        if (manualOperationInProgress) {
            Log.i(TAG, "automatic health cycle skipped: manual service check is running")
            return
        }

        val cycle = ++cycleNumber
''',
            "skip auto cycle during manual operation",
        ),
        (
'''        val candidates = results.filter(::eligible)
        val nextCheckAt = System.currentTimeMillis() + HEALTH_INTERVAL_MS

        if (candidates.isEmpty()) {
''',
'''        val candidates = results.filter(::eligible)
        val nextCheckAt = System.currentTimeMillis() + HEALTH_INTERVAL_MS

        manualLockedTag?.let { lockedTag ->
            if (currentTag != lockedTag) {
                // The user changed the selector outside the manual check UI.
                // Respect that as an explicit unlock.
                Log.i(TAG, "MANUAL LOCK released: selector changed $lockedTag -> $currentTag")
                manualLockedTag = null
            } else {
                val locked = results.firstOrNull { it.tag == lockedTag }
                when {
                    locked != null && eligible(locked) -> {
                        BelkaVpnState.finishSelected(
                            lockedTag,
                            "РУЧНОЙ HOLD ${lockedTag.uppercase(Locale.US)}",
                            nextCheckAt,
                        )
                        Log.i(TAG, "MANUAL LOCK keep $lockedTag: healthy")
                        return
                    }

                    locked != null && fallbackEligible(locked) -> {
                        BelkaVpnState.finishDegraded(
                            lockedTag,
                            "РУЧНОЙ HOLD ${lockedTag.uppercase(Locale.US)} · деградация",
                            nextCheckAt,
                        )
                        Log.w(TAG, "MANUAL LOCK keep $lockedTag: degraded")
                        return
                    }

                    candidates.isNotEmpty() || results.any(::fallbackEligible) -> {
                        // Other servers may work, but a manual selection must
                        // never be silently replaced by the automatic selector.
                        BelkaVpnState.finishDegraded(
                            lockedTag,
                            "РУЧНОЙ HOLD ${lockedTag.uppercase(Locale.US)} · сервис недоступен; авто-переключение отключено",
                            nextCheckAt,
                        )
                        Log.w(TAG, "MANUAL LOCK keep failed $lockedTag; automatic alternatives ignored")
                        return
                    }

                    else -> {
                        finishNoCandidate(
                            cycle,
                            lockedTag,
                            nextCheckAt,
                            "manual locked server unavailable and no alternatives",
                        )
                        return
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
''',
            "manual selector lock",
        ),
        (
'''    private fun probeTelegram(
        node: HealthConfigPatcher.Node,
''',
'''    internal suspend fun manualCheckCurrent(service: BelkaManualService): BelkaManualResult {
        if (manualOperationInProgress) {
            return BelkaManualResult(
                service = service,
                message = "Другая ручная проверка уже выполняется.",
            )
        }

        manualOperationInProgress = true
        return try {
            val currentTag = clashCurrent()
                ?.takeIf { tag -> plan.nodes.any { it.tag == tag } }
                ?: BelkaVpnState.state.value.selectedTag
            val node = plan.nodes.firstOrNull { it.tag == currentTag }
                ?: return BelkaManualResult(
                    service = service,
                    tag = currentTag,
                    canFind = plan.nodes.isNotEmpty(),
                    message = "Текущий VPN-сервер не определён.",
                )

            val telegramDcs = if (service == BelkaManualService.TELEGRAM) {
                readTelegramCache()?.second ?: loadTelegramDcs()
            } else {
                null
            }
            if (service == BelkaManualService.TELEGRAM && telegramDcs == null) {
                return BelkaManualResult(
                    service = service,
                    tag = node.tag,
                    canFind = true,
                    message = "Не удалось получить список Telegram DC для проверки.",
                )
            }

            val probe = manualServiceProbe(node, service, telegramDcs)
            BelkaManualResult(
                service = service,
                tag = node.tag,
                ok = probe.first,
                latencyMs = probe.second,
                canFind = !probe.first,
                message = if (probe.first) {
                    "Сервис отвечает через текущую локацию."
                } else {
                    "Сервис не отвечает через текущую локацию. Найти рабочий сервер?"
                },
            )
        } finally {
            manualOperationInProgress = false
        }
    }

    internal suspend fun manualFindAndSwitch(service: BelkaManualService): BelkaManualResult {
        if (manualOperationInProgress) {
            return BelkaManualResult(
                service = service,
                message = "Другая ручная проверка уже выполняется.",
            )
        }

        manualOperationInProgress = true
        return try {
            val telegramDcs = if (service == BelkaManualService.TELEGRAM) {
                readTelegramCache()?.second ?: loadTelegramDcs()
            } else {
                null
            }
            if (service == BelkaManualService.TELEGRAM && telegramDcs == null) {
                return BelkaManualResult(
                    service = service,
                    message = "Не удалось получить список Telegram DC. Поиск сервера отменён.",
                )
            }

            val probes = supervisorScope {
                plan.nodes.map { node ->
                    async(Dispatchers.IO) {
                        node to manualServiceProbe(node, service, telegramDcs)
                    }
                }.awaitAll()
            }

            val best = probes
                .filter { it.second.first }
                .minByOrNull { it.second.second ?: Double.MAX_VALUE }

            if (best == null) {
                val underlaySpeed = probeUnderlaySpeed()
                if (isLowInternet(underlaySpeed)) {
                    lowInternetMode = true
                    BelkaVpnState.finishLowInternet(
                        clashCurrent(),
                        underlaySpeed,
                        System.currentTimeMillis() + HEALTH_INTERVAL_MS,
                    )
                }
                return BelkaManualResult(
                    service = service,
                    message = if (isLowInternet(underlaySpeed)) {
                        underlaySpeed?.let {
                            "Рабочий VPN-сервер не найден; физический интернет медленный (${fmt1(it)} Мбит/с)."
                        } ?: "Рабочий VPN-сервер не найден; физический интернет нестабилен."
                    } else {
                        "Рабочий VPN-сервер для ${service.displayName} не найден."
                    },
                )
            }

            val node = best.first
            val latency = best.second.second
            clashSelect(node.tag)
            manualLockedTag = node.tag
            lowInternetMode = false
            BelkaVpnState.finishSelected(
                node.tag,
                "РУЧНОЙ → ${node.tag.uppercase(Locale.US)} · ${service.displayName}",
                System.currentTimeMillis() + HEALTH_INTERVAL_MS,
            )
            Log.i(
                TAG,
                "MANUAL ${service.displayName} SELECT -> ${node.tag}, ${formatMs(latency)}; auto switch locked",
            )

            BelkaManualResult(
                service = service,
                tag = node.tag,
                ok = true,
                latencyMs = latency,
                switched = true,
                message = "Найден рабочий сервер и выполнено ручное переключение.",
            )
        } finally {
            manualOperationInProgress = false
        }
    }

    private fun manualServiceProbe(
        node: HealthConfigPatcher.Node,
        service: BelkaManualService,
        telegramDcs: Map<Int, List<DcEndpoint>>?,
    ): Pair<Boolean, Double?> = when (service) {
        BelkaManualService.TELEGRAM -> {
            val dcs = telegramDcs ?: return false to null
            val deadline = System.nanoTime() + 18_000L * 1_000_000L
            val result = runCatching {
                probeTelegram(node, dcs, deadline, 0L)
            }.getOrElse {
                Log.w(TAG, "MANUAL Telegram ${node.tag} failed: ${it.message}")
                failedTelegram(dcs.size)
            }
            (result.ok && result.medianMs != null) to result.medianMs
        }

        BelkaManualService.INSTAGRAM -> {
            val result = runCatching { probeInstagram(node) }
                .getOrElse {
                    Log.w(TAG, "MANUAL Instagram ${node.tag} failed: ${it.message}")
                    failedInstagram()
                }
            (result.apiOk && result.apiMs != null) to result.apiMs
        }
    }

    private fun probeTelegram(
        node: HealthConfigPatcher.Node,
''',
            "manual service probes",
        ),
    ],
)

patch_file(
    "app/src/main/java/io/nekohasekai/sfa/compose/screen/dashboard/DashboardScreen.kt",
    [
        (
'''                            belkaVpnState.lowInternet -> "🟠 Низкая скорость интернета"
                            belkaVpnState.degraded && belkaVpnState.selectedTag != null ->
''',
'''                            belkaVpnState.lowInternet -> "🟠 Низкая скорость интернета"
                            belkaVpnState.lastAction.startsWith("РУЧНОЙ") && belkaVpnState.selectedTag != null ->
                                "🔒 ${BelkaVpnState.countryLabel(belkaVpnState.selectedTag)} · вручную"
                            belkaVpnState.degraded && belkaVpnState.selectedTag != null ->
''',
            "manual lock dashboard status",
        ),
    ],
)

patch_file(
    "app/src/main/java/io/nekohasekai/sfa/compose/screen/dashboard/BelkaSmartStatusCard.kt",
    [
        (
'''import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
''',
'''import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
''',
            "weight import",
        ),
        (
'''import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
''',
'''import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
''',
            "coroutine scope import",
        ),
        (
'''import io.nekohasekai.sfa.bg.health.BelkaNodeStatus
import io.nekohasekai.sfa.bg.health.BelkaUpdateUiState
''',
'''import io.nekohasekai.sfa.bg.health.BelkaManualChecks
import io.nekohasekai.sfa.bg.health.BelkaManualResult
import io.nekohasekai.sfa.bg.health.BelkaManualService
import io.nekohasekai.sfa.bg.health.BelkaNodeStatus
import io.nekohasekai.sfa.bg.health.BelkaUpdateUiState
''',
            "manual check imports",
        ),
        (
'''import java.util.Locale
''',
'''import java.util.Locale
import kotlinx.coroutines.launch
''',
            "launch import",
        ),
        (
'''    var showDetails by remember { mutableStateOf(false) }

    if (showDetails) {
''',
'''    var showDetails by remember { mutableStateOf(false) }
    var manualResult by remember { mutableStateOf<BelkaManualResult?>(null) }
    var manualChecking by remember { mutableStateOf<BelkaManualService?>(null) }
    var manualFinding by remember { mutableStateOf<BelkaManualService?>(null) }
    val manualScope = rememberCoroutineScope()

    if (showDetails) {
''',
            "manual UI state",
        ),
        (
'''    if (showDetails) {
        BelkaSmartDetailsDialog(
            vpnEnabled = vpnEnabled,
            vpnState = vpnState,
            onDismiss = { showDetails = false },
        )
    }

    Card(
''',
'''    if (showDetails) {
        BelkaSmartDetailsDialog(
            vpnEnabled = vpnEnabled,
            vpnState = vpnState,
            onDismiss = { showDetails = false },
        )
    }

    manualResult?.let { result ->
        ManualCheckDialog(
            result = result,
            finding = manualFinding == result.service,
            onFind = {
                manualFinding = result.service
                manualScope.launch {
                    manualResult = BelkaManualChecks.findAndSwitch(result.service)
                    manualFinding = null
                }
            },
            onDismiss = {
                if (manualFinding == null) manualResult = null
            },
        )
    }

    Card(
''',
            "manual result dialog",
        ),
        (
'''                        vpnState.checking -> "🟡 проверка"
                        vpnState.lowInternet -> "🟠 низкая скорость интернета"
                        vpnState.degraded && vpnState.selectedTag != null -> "🟠 деградация"
''',
'''                        vpnState.checking -> "🟡 проверка"
                        vpnState.lowInternet -> "🟠 низкая скорость интернета"
                        vpnState.lastAction.startsWith("РУЧНОЙ") -> "🔒 ручной"
                        vpnState.degraded && vpnState.selectedTag != null -> "🟠 деградация"
''',
            "manual lock card status",
        ),
        (
'''            Text(
                text = details,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when {
''',
'''            Text(
                text = details,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = "Ручная проверка",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = {
                        manualChecking = BelkaManualService.TELEGRAM
                        manualScope.launch {
                            manualResult = BelkaManualChecks.checkCurrent(BelkaManualService.TELEGRAM)
                            manualChecking = null
                        }
                    },
                    enabled = vpnEnabled && manualChecking == null && manualFinding == null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (manualChecking == BelkaManualService.TELEGRAM) "Проверка…" else "Telegram")
                }
                FilledTonalButton(
                    onClick = {
                        manualChecking = BelkaManualService.INSTAGRAM
                        manualScope.launch {
                            manualResult = BelkaManualChecks.checkCurrent(BelkaManualService.INSTAGRAM)
                            manualChecking = null
                        }
                    },
                    enabled = vpnEnabled && manualChecking == null && manualFinding == null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (manualChecking == BelkaManualService.INSTAGRAM) "Проверка…" else "Instagram")
                }
            }

            when {
''',
            "manual check buttons",
        ),
        (
'''@Composable
private fun BelkaSmartDetailsDialog(
''',
'''@Composable
private fun ManualCheckDialog(
    result: BelkaManualResult,
    finding: Boolean,
    onFind: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = when {
        result.switched -> "${result.service.displayName} · переключено"
        result.ok -> "${result.service.displayName} работает"
        result.canFind -> "${result.service.displayName} не работает"
        result.tag == null -> "Проверка ${result.service.displayName}"
        else -> "${result.service.displayName} · сервер не найден"
    }
    val body = buildString {
        append(result.message)
        result.tag?.let {
            append("\n\n")
            append(BelkaVpnState.countryLabel(it))
        }
        result.latencyMs?.let {
            append(" · ${it.toInt()} ms")
        }
        if (result.switched) {
            append("\n\n🔒 Автопереключение отключено для этой ручной локации. Lock снимется, если вы сами смените сервер или перезапустите VPN.")
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (!finding) onDismiss()
        },
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            if (result.canFind) {
                TextButton(
                    onClick = onFind,
                    enabled = !finding,
                ) {
                    Text(if (finding) "Поиск…" else "Найти и переключить")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Закрыть")
                }
            }
        },
        dismissButton = if (result.canFind) {
            {
                TextButton(
                    onClick = onDismiss,
                    enabled = !finding,
                ) {
                    Text("Отмена")
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun BelkaSmartDetailsDialog(
''',
            "manual result dialog composable",
        ),
    ],
)

print("BelkaVPN manual service checks v7 applied:")
print("- main screen buttons for Telegram and Instagram")
print("- current location is checked on demand")
print("- failed check offers Find and switch")
print("- search probes all VPN nodes only for the selected service")
print("- best working node is selected by latency")
print("- manual selection locks automatic Smart VPN switching")
print("- lock is released by an external manual selector change or VPN restart")
