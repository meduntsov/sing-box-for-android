#!/usr/bin/env python3
from pathlib import Path
import re


def patch_exact(path_str: str, old: str, new: str, label: str) -> None:
    path = Path(path_str)
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path_str}: {label}: expected exactly 1 match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def patch_regex(path_str: str, pattern: str, replacement: str, label: str) -> None:
    path = Path(path_str)
    text = path.read_text(encoding="utf-8")
    text, count = re.subn(pattern, replacement, text, count=1, flags=re.MULTILINE)
    if count != 1:
        raise SystemExit(f"{path_str}: {label}: expected exactly 1 match, found {count}")
    path.write_text(text, encoding="utf-8")


# AI selector: keep DE as auto default, but allow any concrete node for manual lock.
patch_exact(
    "app/src/main/java/io/nekohasekai/sfa/bg/health/HealthConfigPatcher.kt",
    '''            aiSelector.put(
                "outbounds",
                JSONArray()
                    .put(aiPreferredTag)
                    .put(SELECTOR_TAG),
            )
''',
    '''            val aiOutbounds = JSONArray().put(aiPreferredTag)
            leafTags.filterNot { it == aiPreferredTag }.forEach(aiOutbounds::put)
            aiOutbounds.put(SELECTOR_TAG)
            aiSelector.put("outbounds", aiOutbounds)
''',
    "AI selector concrete nodes",
)

# UI state for a session-only manual lock.
patch_exact(
    "app/src/main/java/io/nekohasekai/sfa/bg/health/BelkaVpnState.kt",
    '''    val lastAction: String = "",
)
''',
    '''    val lastAction: String = "",
    val manualLockedTag: String? = null,
)
''',
    "manual lock field",
)
patch_exact(
    "app/src/main/java/io/nekohasekai/sfa/bg/health/BelkaVpnState.kt",
    '''    fun countryLabel(tag: String?): String {
''',
    '''    fun setManualLock(tag: String) {
        _state.update {
            it.copy(
                selectedTag = tag,
                checking = false,
                noEligible = false,
                degraded = false,
                manualLockedTag = tag,
                lastAction = "РУЧНОЙ HOLD ${tag.uppercase(Locale.US)}",
            )
        }
    }

    fun clearManualLock() {
        _state.update { it.copy(manualLockedTag = null) }
    }

    fun countryLabel(tag: String?): String {
''',
    "manual lock state methods",
)

# Manual facade used by the dashboard.
patch_exact(
    "app/src/main/java/io/nekohasekai/sfa/bg/health/BelkaManualChecks.kt",
    '''    suspend fun findAndSwitch(service: BelkaManualService): BelkaManualResult =
''',
    '''    fun availableServers(): List<String> =
        controller?.manualAvailableTags().orEmpty()

    suspend fun selectServer(tag: String): Boolean =
        withContext(Dispatchers.IO) {
            val active = controller ?: return@withContext false
            runCatching { active.manualSelectServer(tag) }.getOrDefault(false)
        }

    suspend fun findAndSwitch(service: BelkaManualService): BelkaManualResult =
''',
    "manual server facade",
)

health = "app/src/main/java/io/nekohasekai/sfa/bg/health/HealthController.kt"
patch_exact(
    health,
    '''    override fun close() {
        Log.i(TAG, "v2.2 STOP")
        BelkaManualChecks.detach(this)
''',
    '''    override fun close() {
        Log.i(TAG, "v2.2 STOP")
        manualLockedTag = null
        BelkaVpnState.clearManualLock()
        BelkaManualChecks.detach(this)
''',
    "clear lock on stop",
)
patch_exact(
    health,
    '''                Log.i(TAG, "MANUAL LOCK released: selector changed $lockedTag -> $currentTag")
                manualLockedTag = null
''',
    '''                Log.i(TAG, "MANUAL LOCK released: selector changed $lockedTag -> $currentTag")
                manualLockedTag = null
                BelkaVpnState.clearManualLock()
''',
    "clear UI lock on external change",
)
patch_exact(
    health,
    '''    internal suspend fun manualCheckCurrent(service: BelkaManualService): BelkaManualResult {
''',
    '''    internal fun manualAvailableTags(): List<String> = plan.nodes.map { it.tag }

    internal fun manualSelectServer(tag: String): Boolean {
        val node = plan.nodes.firstOrNull { it.tag == tag } ?: return false
        manualApplySelection(node.tag)
        manualLockedTag = node.tag
        lowInternetMode = false
        BelkaVpnState.setManualLock(node.tag)
        Log.i(TAG, "MANUAL SERVER SELECT -> ${node.tag}; locked until VPN/app restart")
        return true
    }

    private fun manualApplySelection(tag: String) {
        clashSelectNamed(plan.selectorTag, tag)
        plan.aiSelectorTag?.let { aiSelector ->
            runCatching { clashSelectNamed(aiSelector, tag) }
                .onFailure { Log.w(TAG, "MANUAL AI selector $aiSelector -> $tag failed: ${it.message}") }
        }
    }

    internal suspend fun manualCheckCurrent(service: BelkaManualService): BelkaManualResult {
''',
    "manual select API",
)
patch_exact(
    health,
    '''            clashSelect(node.tag)
            manualLockedTag = node.tag
            lowInternetMode = false
''',
    '''            manualApplySelection(node.tag)
            manualLockedTag = node.tag
            lowInternetMode = false
            BelkaVpnState.setManualLock(node.tag)
''',
    "service find uses full manual lock",
)

# Robustly split the existing selector helper. Match only the stable function
# signature + encoder line instead of an entire implementation body.
patch_regex(
    health,
    r'''    private fun clashSelect\(tag: String\) \{\n\s*val encoded = URLEncoder\.encode\(plan\.selectorTag, "UTF-8"\)\.replace\("\+", "%20"\)\n''',
    '''    private fun clashSelect(tag: String) {
        clashSelectNamed(plan.selectorTag, tag)
    }

    private fun clashSelectNamed(selectorTag: String, tag: String) {
        val encoded = URLEncoder.encode(selectorTag, "UTF-8").replace("+", "%20")
''',
    "selector helper anchor",
)

screen = "app/src/main/java/io/nekohasekai/sfa/compose/screen/dashboard/DashboardScreen.kt"
patch_exact(
    screen,
    '''import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
''',
    '''import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
''',
    "Card import",
)
patch_exact(
    screen,
    '''import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
''',
    '''import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
''',
    "TextButton import",
)
patch_exact(
    screen,
    '''import io.nekohasekai.sfa.bg.health.BelkaUpdateManager
import io.nekohasekai.sfa.bg.health.BelkaVpnState
''',
    '''import io.nekohasekai.sfa.bg.health.BelkaManualChecks
import io.nekohasekai.sfa.bg.health.BelkaUpdateManager
import io.nekohasekai.sfa.bg.health.BelkaVpnState
''',
    "manual facade import",
)
patch_exact(
    screen,
    '''    var showOthersMenu by remember { mutableStateOf(false) }
''',
    '''    var showOthersMenu by remember { mutableStateOf(false) }
    var showServerMenu by remember { mutableStateOf(false) }
    var serverSelectionBusy by remember { mutableStateOf(false) }
    val manualServerScope = rememberCoroutineScope()
''',
    "manual selector UI state",
)
patch_exact(
    screen,
    '''            if (!isRemote) {
                item {
                    BelkaSmartStatusCard(
''',
    '''            if (!isRemote) {
                item {
                    val serverTags = BelkaManualChecks.availableServers()
                    val selectedTag = belkaVpnState.selectedTag
                    val lockedTag = belkaVpnState.manualLockedTag

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.fillMaxWidth(0.70f)) {
                                Text(
                                    text = if (lockedTag != null) "🔒 Ручной сервер" else "Ручной выбор сервера",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = when {
                                        selectedTag != null -> BelkaVpnState.countryLabel(selectedTag)
                                        serviceStatus == Status.Started -> "VPN запускается…"
                                        else -> "VPN отключён"
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                )
                            }

                            Box {
                                TextButton(
                                    enabled = serviceStatus == Status.Started && serverTags.isNotEmpty() && !serverSelectionBusy,
                                    onClick = { showServerMenu = true },
                                ) {
                                    Text(if (serverSelectionBusy) "…" else "Выбрать")
                                }
                                DropdownMenu(
                                    expanded = showServerMenu,
                                    onDismissRequest = { showServerMenu = false },
                                ) {
                                    serverTags.forEach { tag ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(buildString {
                                                    if (tag == lockedTag) append("🔒 ")
                                                    append(BelkaVpnState.countryLabel(tag))
                                                })
                                            },
                                            onClick = {
                                                showServerMenu = false
                                                serverSelectionBusy = true
                                                manualServerScope.launch {
                                                    BelkaManualChecks.selectServer(tag)
                                                    serverSelectionBusy = false
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    BelkaSmartStatusCard(
''',
    "manual selector card",
)

print("BelkaVPN robust manual server selector v8 applied")
print("- top dashboard picker with flags")
print("- general + AI selectors locked to selected node")
print("- lock clears on VPN stop/start or app restart")
