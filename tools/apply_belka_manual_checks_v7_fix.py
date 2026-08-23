#!/usr/bin/env python3
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, found {count}")
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# v7 compile fixes + v8 session-wide manual server lock
# ---------------------------------------------------------------------------
health = Path("app/src/main/java/io/nekohasekai/sfa/bg/health/HealthController.kt")
text = health.read_text(encoding="utf-8")

text = replace_once(
    text,
'''    private fun manualServiceProbe(
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
''',
'''    private fun manualServiceProbe(
        node: HealthConfigPatcher.Node,
        service: BelkaManualService,
        telegramDcs: Map<Int, List<DcEndpoint>>?,
    ): Pair<Boolean, Double?> {
        return when (service) {
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
    }
''',
    "manualServiceProbe block body",
)

text = replace_once(
    text,
'''        BelkaManualChecks.attach(this)
        Log.i(TAG, "v2.2 START selector=${plan.selectorTag}, nodes=${plan.nodes.joinToString(",") { it.tag }}")
''',
'''        BelkaManualChecks.attach(this)
        BelkaVpnState.setAvailableTags(plan.nodes.map { it.tag })
        Log.i(TAG, "v2.2 START selector=${plan.selectorTag}, nodes=${plan.nodes.joinToString(",") { it.tag }}")
''',
    "publish available server tags",
)

text = replace_once(
    text,
'''        BelkaManualChecks.detach(this)
        loopJob?.cancel()
''',
'''        BelkaManualChecks.detach(this)
        manualLockedTag = null
        BelkaVpnState.clearManualSession()
        loopJob?.cancel()
''',
    "clear manual lock on VPN stop/restart",
)

text = replace_once(
    text,
'''        manualLockedTag?.let { lockedTag ->
            if (currentTag != lockedTag) {
                // The user changed the selector outside the manual check UI.
                // Respect that as an explicit unlock.
                Log.i(TAG, "MANUAL LOCK released: selector changed $lockedTag -> $currentTag")
                manualLockedTag = null
            } else {
                val locked = results.firstOrNull { it.tag == lockedTag }
''',
'''        manualLockedTag?.let { lockedTag ->
            if (currentTag != lockedTag) {
                // A session manual choice is authoritative until the VPN is
                // stopped/restarted. If anything changes the selector behind
                // us, restore the user's locked location instead of unlocking.
                Log.w(TAG, "MANUAL LOCK restore $lockedTag (selector was $currentTag)")
                clashSelect(lockedTag)
                clashSelectAiThroughProxy()
            }
            if (manualLockedTag == lockedTag) {
                val locked = results.firstOrNull { it.tag == lockedTag }
''',
    "make manual lock authoritative for session",
)

text = replace_once(
    text,
'''            val node = best.first
            val latency = best.second.second
            clashSelect(node.tag)
            manualLockedTag = node.tag
            lowInternetMode = false
''',
'''            val node = best.first
            val latency = best.second.second
            clashSelect(node.tag)
            clashSelectAiThroughProxy()
            manualLockedTag = node.tag
            BelkaVpnState.setManualLocked(node.tag)
            lowInternetMode = false
''',
    "service-specific manual switch locks all traffic",
)

text = replace_once(
    text,
'''    internal suspend fun manualCheckCurrent(service: BelkaManualService): BelkaManualResult {
''',
'''    internal suspend fun manualSelectServer(tag: String): Boolean {
        if (manualOperationInProgress) return false
        val node = plan.nodes.firstOrNull { it.tag == tag } ?: return false

        manualOperationInProgress = true
        return try {
            clashSelect(node.tag)
            // AI has its own selector in the patched config. Point that
            // selector at the main proxy so ChatGPT/Claude/Gemini follow the
            // same manually selected country as every other VPN flow.
            clashSelectAiThroughProxy()
            manualLockedTag = node.tag
            lowInternetMode = false
            BelkaVpnState.setManualLocked(node.tag)
            BelkaVpnState.finishSelected(
                node.tag,
                "РУЧНОЙ → ${node.tag.uppercase(Locale.US)} · весь трафик",
                System.currentTimeMillis() + HEALTH_INTERVAL_MS,
            )
            Log.i(TAG, "MANUAL MASTER SELECT -> ${node.tag}; locked until VPN restart")
            true
        } catch (e: Exception) {
            Log.w(TAG, "MANUAL MASTER SELECT $tag failed: ${e.message}", e)
            false
        } finally {
            manualOperationInProgress = false
        }
    }

    internal suspend fun manualCheckCurrent(service: BelkaManualService): BelkaManualResult {
''',
    "manual master selector API",
)

text = replace_once(
    text,
'''    private fun clashSelect(tag: String) {
        val encoded = URLEncoder.encode(plan.selectorTag, "UTF-8").replace("+", "%20")
        val connection = localClash("http://127.0.0.1:${plan.clashPort}/proxies/$encoded")
        connection.requestMethod = "PUT"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject().put("name", tag).toString().toByteArray(Charsets.UTF_8)
        connection.outputStream.use { it.write(body) }
        val code = connection.responseCode
        connection.disconnect()
        require(code in 200..299) { "Clash selector returned HTTP $code" }
    }
''',
'''    private fun clashSelect(tag: String) {
        clashSelectOn(plan.selectorTag, tag)
    }

    private fun clashSelectAiThroughProxy() {
        val aiSelector = plan.aiSelectorTag ?: return
        clashSelectOn(aiSelector, plan.selectorTag)
    }

    private fun clashSelectOn(selectorTag: String, tag: String) {
        val encoded = URLEncoder.encode(selectorTag, "UTF-8").replace("+", "%20")
        val connection = localClash("http://127.0.0.1:${plan.clashPort}/proxies/$encoded")
        connection.requestMethod = "PUT"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject().put("name", tag).toString().toByteArray(Charsets.UTF_8)
        connection.outputStream.use { it.write(body) }
        val code = connection.responseCode
        connection.disconnect()
        require(code in 200..299) {
            "Clash selector $selectorTag -> $tag returned HTTP $code"
        }
    }
''',
    "generic Clash selector",
)
health.write_text(text, encoding="utf-8")


# ---------------------------------------------------------------------------
# Session state exposed to Compose
# ---------------------------------------------------------------------------
state_path = Path("app/src/main/java/io/nekohasekai/sfa/bg/health/BelkaVpnState.kt")
state = state_path.read_text(encoding="utf-8")
state = replace_once(
    state,
'''    val lowInternet: Boolean = false,
    val underlaySpeedMbps: Double? = null,
    val nodes: Map<String, BelkaNodeStatus> = emptyMap(),
''',
'''    val lowInternet: Boolean = false,
    val underlaySpeedMbps: Double? = null,
    val availableTags: List<String> = emptyList(),
    val manualLockedTag: String? = null,
    val nodes: Map<String, BelkaNodeStatus> = emptyMap(),
''',
    "manual selector UI state fields",
)
state = replace_once(
    state,
'''    val state: StateFlow<BelkaVpnUiState> = _state.asStateFlow()

    fun cycleStart(currentTag: String?, nextCheckAt: Long) {
''',
'''    val state: StateFlow<BelkaVpnUiState> = _state.asStateFlow()

    fun setAvailableTags(tags: List<String>) {
        _state.update { it.copy(availableTags = tags.distinct()) }
    }

    fun setManualLocked(tag: String) {
        _state.update { it.copy(manualLockedTag = tag) }
    }

    fun clearManualSession() {
        _state.update {
            it.copy(
                manualLockedTag = null,
                availableTags = emptyList(),
            )
        }
    }

    fun cycleStart(currentTag: String?, nextCheckAt: Long) {
''',
    "manual selector state methods",
)
state_path.write_text(state, encoding="utf-8")


# ---------------------------------------------------------------------------
# Public facade for the Dashboard selector
# ---------------------------------------------------------------------------
manual_path = Path("app/src/main/java/io/nekohasekai/sfa/bg/health/BelkaManualChecks.kt")
manual = manual_path.read_text(encoding="utf-8")
manual = replace_once(
    manual,
'''    private fun unavailable(service: BelkaManualService) =
''',
'''    suspend fun selectServer(tag: String): Boolean =
        withContext(Dispatchers.IO) {
            val active = controller ?: return@withContext false
            runCatching { active.manualSelectServer(tag) }.getOrDefault(false)
        }

    private fun unavailable(service: BelkaManualService) =
''',
    "manual selector facade",
)
manual_path.write_text(manual, encoding="utf-8")


# ---------------------------------------------------------------------------
# Dashboard: top manual country/server selector with flag
# ---------------------------------------------------------------------------
dash_path = Path("app/src/main/java/io/nekohasekai/sfa/compose/screen/dashboard/DashboardScreen.kt")
dash = dash_path.read_text(encoding="utf-8")
dash = replace_once(
    dash,
'''import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
''',
'''import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
''',
    "FilledTonalButton import",
)
dash = replace_once(
    dash,
'''import io.nekohasekai.sfa.bg.health.BelkaUpdateManager
import io.nekohasekai.sfa.bg.health.BelkaVpnState
''',
'''import io.nekohasekai.sfa.bg.health.BelkaManualChecks
import io.nekohasekai.sfa.bg.health.BelkaUpdateManager
import io.nekohasekai.sfa.bg.health.BelkaVpnState
import io.nekohasekai.sfa.bg.health.BelkaVpnUiState
''',
    "manual dashboard imports",
)
dash = dash.replace(
    'belkaVpnState.lastAction.startsWith("РУЧНОЙ") && belkaVpnState.selectedTag != null',
    'belkaVpnState.manualLockedTag != null && belkaVpnState.selectedTag != null',
)
dash = replace_once(
    dash,
'''            if (!isRemote) {
                item {
                    BelkaSmartStatusCard(
''',
'''            if (!isRemote) {
                item {
                    BelkaManualServerSelector(
                        vpnEnabled = serviceStatus == Status.Started,
                        vpnState = belkaVpnState,
                    )
                }
                item {
                    BelkaSmartStatusCard(
''',
    "manual selector before Smart VPN card",
)

if "private fun BelkaManualServerSelector(" in dash:
    raise SystemExit("DashboardScreen: manual selector already present")

dash += r'''

@Composable
private fun BelkaManualServerSelector(
    vpnEnabled: Boolean,
    vpnState: BelkaVpnUiState,
) {
    var expanded by remember { mutableStateOf(false) }
    var switchingTag by remember { mutableStateOf<String?>(null) }
    var selectionError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val tags = vpnState.availableTags
    val lockedTag = vpnState.manualLockedTag
    val displayTag = lockedTag ?: vpnState.selectedTag

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = if (lockedTag != null) "🔒 Ручной сервер" else "Ручной выбор сервера",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Box(modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(
                onClick = { expanded = true },
                enabled = vpnEnabled && tags.isNotEmpty() && switchingTag == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = when {
                        switchingTag != null -> "Переключение…"
                        displayTag != null -> BelkaVpnState.countryLabel(displayTag)
                        vpnEnabled -> "Выбрать сервер"
                        else -> "VPN отключён"
                    },
                    maxLines = 1,
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                tags.forEach { tag ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                BelkaVpnState.countryLabel(tag) +
                                    if (tag == displayTag) "  ✓" else "",
                            )
                        },
                        onClick = {
                            expanded = false
                            switchingTag = tag
                            selectionError = false
                            scope.launch {
                                val ok = BelkaManualChecks.selectServer(tag)
                                selectionError = !ok
                                switchingTag = null
                            }
                        },
                    )
                }
            }
        }

        Text(
            text = when {
                selectionError -> "Не удалось переключить сервер. Проверьте VPN и повторите."
                lockedTag != null ->
                    "Весь VPN-трафик закреплён за этой локацией до выключения/включения VPN или перезапуска приложения."
                vpnEnabled ->
                    "После ручного выбора Smart VPN продолжит диагностику, но не будет автоматически менять локацию."
                else -> "Запустите VPN, чтобы выбрать локацию вручную."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
'''
dash_path.write_text(dash, encoding="utf-8")


# ---------------------------------------------------------------------------
# Existing Smart VPN card should show the explicit state flag, not lastAction.
# Also fix Python-generated Kotlin newlines from v7 dialog text.
# ---------------------------------------------------------------------------
card_path = Path("app/src/main/java/io/nekohasekai/sfa/compose/screen/dashboard/BelkaSmartStatusCard.kt")
card = card_path.read_text(encoding="utf-8")

card = replace_once(
    card,
'''import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
''',
'''import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
''',
    "remove invalid weight import",
)

plain_break = 'append("\n\n")'
escaped_break = 'append("\\n\\n")'
count = card.count(plain_break)
if count != 1:
    raise SystemExit(f"plain dialog newline fix: expected exactly 1 match, found {count}")
card = card.replace(plain_break, escaped_break, 1)

lock_prefix = 'append("\n\n🔒 Автопереключение'
escaped_lock_prefix = 'append("\\n\\n🔒 Автопереключение'
count = card.count(lock_prefix)
if count != 1:
    raise SystemExit(f"lock dialog newline fix: expected exactly 1 match, found {count}")
card = card.replace(lock_prefix, escaped_lock_prefix, 1)

card = card.replace(
    'vpnState.lastAction.startsWith("РУЧНОЙ")',
    'vpnState.manualLockedTag != null',
)
card = card.replace(
    'Lock снимется, если вы сами смените сервер или перезапустите VPN.',
    'Lock действует до выключения/включения VPN или перезапуска приложения.',
)
card_path.write_text(card, encoding="utf-8")

print("BelkaVPN manual selector v8 applied:")
print("- top dashboard server picker with country flag")
print("- manual selection locks the main selector for the VPN session")
print("- AI selector follows the same manually selected proxy")
print("- automatic health checks cannot override the manual lock")
print("- lock clears only when VPN/controller stops or restarts")
print("- v7 Kotlin string/import fixes retained")
