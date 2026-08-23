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


# Allow the AI selector to target every concrete node. Auto mode still defaults
# to DE; manual mode can then pin AI traffic to the same location as all other
# selector traffic.
patch_file(
    "app/src/main/java/io/nekohasekai/sfa/bg/health/HealthConfigPatcher.kt",
    [
        (
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
            "AI selector all concrete nodes",
        ),
    ],
)

patch_file(
    "app/src/main/java/io/nekohasekai/sfa/bg/health/BelkaVpnState.kt",
    [
        (
'''    val lastAction: String = "",
)
''',
'''    val lastAction: String = "",
    val manualLockedTag: String? = null,
)
''',
            "manual lock state field",
        ),
        (
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
        ),
    ],
)

patch_file(
    "app/src/main/java/io/nekohasekai/sfa/bg/health/BelkaManualChecks.kt",
    [
        (
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
        ),
    ],
)

patch_file(
    "app/src/main/java/io/nekohasekai/sfa/bg/health/HealthController.kt",
    [
        (
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
            "clear lock on VPN stop",
        ),
        (
'''                Log.i(TAG, "MANUAL LOCK released: selector changed $lockedTag -> $currentTag")
                manualLockedTag = null
''',
'''                Log.i(TAG, "MANUAL LOCK released: selector changed $lockedTag -> $currentTag")
                manualLockedTag = null
                BelkaVpnState.clearManualLock()
''',
            "clear UI lock after external selector change",
        ),
        (
'''    internal suspend fun manualCheckCurrent(service: BelkaManualService): BelkaManualResult {
''',
'''    internal fun manualAvailableTags(): List<String> = plan.nodes.map { it.tag }

    internal fun manualSelectServer(tag: String): Boolean {
        val node = plan.nodes.firstOrNull { it.tag == tag } ?: return false
        manualApplySelection(node.tag)
        manualLockedTag = node.tag
        lowInternetMode = false
        BelkaVpnState.setManualLock(node.tag)
        Log.i(TAG, "MANUAL SERVER SELECT -> ${node.tag}; all selector traffic locked until VPN restart")
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
        ),
        (
'''            clashSelect(node.tag)
            manualLockedTag = node.tag
            lowInternetMode = false
''',
'''            manualApplySelection(node.tag)
            manualLockedTag = node.tag
            lowInternetMode = false
            BelkaVpnState.setManualLock(node.tag)
''',
            "service search applies full manual selection",
        ),
        (
'''    private fun clashSelect(tag: String) {
        val encoded = URLEncoder.encode(plan.selectorTag, "UTF-8").replace("+", "%20")
        val connection = localClash("http://127.0.0.1:${plan.clashPort}/proxies/$encoded")
''',
'''    private fun clashSelect(tag: String) {
        clashSelectNamed(plan.selectorTag, tag)
    }

    private fun clashSelectNamed(selectorTag: String, tag: String) {
        val encoded = URLEncoder.encode(selectorTag, "UTF-8").replace("+", "%20")
        val connection = localClash("http://127.0.0.1:${plan.clashPort}/proxies/$encoded")
''',
            "named selector helper",
        ),
    ],
)

patch_file(
    "app/src/main/java/io/nekohasekai/sfa/compose/screen/dashboard/DashboardScreen.kt",
    [
        (
'''import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
''',
'''import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
''',
            "weight import",
        ),
        (
'''import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
''',
'''import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
''',
            "card import",
        ),
        (
'''import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
''',
'''import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
''',
            "text button import",
        ),
        (
'''import io.nekohasekai.sfa.bg.health.BelkaUpdateManager
import io.nekohasekai.sfa.bg.health.BelkaVpnState
''',
'''import io.nekohasekai.sfa.bg.health.BelkaManualChecks
import io.nekohasekai.sfa.bg.health.BelkaUpdateManager
import io.nekohasekai.sfa.bg.health.BelkaVpnState
''',
            "manual facade import",
        ),
        (
'''    var showOthersMenu by remember { mutableStateOf(false) }
''',
'''    var showOthersMenu by remember { mutableStateOf(false) }
    var showServerMenu by remember { mutableStateOf(false) }
    var serverSelectionBusy by remember { mutableStateOf(false) }
    val manualServerScope = rememberCoroutineScope()
''',
            "manual selector UI state",
        ),
        (
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
                            Column(modifier = Modifier.weight(1f)) {
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
                                    enabled = serviceStatus == Status.Started &&
                                        serverTags.isNotEmpty() &&
                                        !serverSelectionBusy,
                                    onClick = { showServerMenu = true },
                                ) {
                                    Text(if (serverSelectionBusy) "Переключение…" else "Выбрать")
                                }
                                DropdownMenu(
                                    expanded = showServerMenu,
                                    onDismissRequest = { showServerMenu = false },
                                ) {
                                    serverTags.forEach { tag ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    buildString {
                                                        if (tag == lockedTag) append("🔒 ")
                                                        append(BelkaVpnState.countryLabel(tag))
                                                    },
                                                )
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
        ),
    ],
)

print("BelkaVPN manual server selector v8 applied:")
print("- top dashboard server picker with country flags")
print("- manual selection locks general and AI selectors to one node")
print("- lock survives health cycles")
print("- lock clears on VPN stop/start or app restart")
