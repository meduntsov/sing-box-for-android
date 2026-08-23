package io.nekohasekai.sfa.bg.health

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

data class BelkaNodeStatus(
    val tag: String,
    val telegramOk: Boolean = false,
    val telegramMs: Double? = null,
    val instagramOk: Boolean = false,
    val instagramMs: Double? = null,
    val webOk: Int = 0,
    val webTotal: Int = 3,
    val webMs: Double? = null,
    val speedMbps: Double? = null,
    val eligible: Boolean = false,
    val quarantineUntil: Long = 0L,
)

data class BelkaVpnUiState(
    val selectedTag: String? = null,
    val checking: Boolean = false,
    val noEligible: Boolean = false,
    val nodes: Map<String, BelkaNodeStatus> = emptyMap(),
    val lastCheckAt: Long = 0L,
    val nextCheckAt: Long = 0L,
    val lastAction: String = "",
)

object BelkaVpnState {
    private val _state = MutableStateFlow(BelkaVpnUiState())
    val state: StateFlow<BelkaVpnUiState> = _state.asStateFlow()

    fun cycleStart(currentTag: String?, nextCheckAt: Long) {
        _state.update {
            it.copy(
                selectedTag = currentTag ?: it.selectedTag,
                checking = true,
                noEligible = false,
                nextCheckAt = nextCheckAt,
                lastAction = "Проверка серверов…",
            )
        }
    }

    fun updateNode(status: BelkaNodeStatus) {
        _state.update { current ->
            current.copy(nodes = current.nodes + (status.tag to status))
        }
    }

    fun finishSelected(tag: String, action: String, nextCheckAt: Long) {
        _state.update {
            it.copy(
                selectedTag = tag,
                checking = false,
                noEligible = false,
                lastCheckAt = System.currentTimeMillis(),
                nextCheckAt = nextCheckAt,
                lastAction = action,
            )
        }
    }

    fun finishNoEligible(currentTag: String?, nextCheckAt: Long) {
        _state.update {
            it.copy(
                selectedTag = currentTag ?: it.selectedTag,
                checking = false,
                noEligible = true,
                lastCheckAt = System.currentTimeMillis(),
                nextCheckAt = nextCheckAt,
                lastAction = "Нет подходящего сервера",
            )
        }
    }

    fun countryLabel(tag: String?): String {
        if (tag.isNullOrBlank()) return "VPN запускается…"
        val normalized = tag.lowercase(Locale.US)
        val region = when (normalized) {
            "uk" -> "GB"
            else -> normalized.uppercase(Locale.US)
        }
        if (region.length != 2) return tag.uppercase(Locale.US)

        val displayCountry = Locale("", region)
            .getDisplayCountry(Locale.getDefault())
            .ifBlank { tag.uppercase(Locale.US) }

        return "${flag(region)} $displayCountry · ${tag.uppercase(Locale.US)}"
    }

    private fun flag(region: String): String {
        if (region.length != 2) return "🌐"
        val first = Character.codePointAt(region, 0) - 'A'.code + 0x1F1E6
        val second = Character.codePointAt(region, 1) - 'A'.code + 0x1F1E6
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }
}
