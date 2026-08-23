package io.nekohasekai.sfa.bg.health

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class BelkaManualService(val displayName: String) {
    TELEGRAM("Telegram"),
    INSTAGRAM("Instagram"),
}

data class BelkaManualResult(
    val service: BelkaManualService,
    val tag: String? = null,
    val ok: Boolean = false,
    val latencyMs: Double? = null,
    val switched: Boolean = false,
    val canFind: Boolean = false,
    val message: String = "",
)

object BelkaManualChecks {
    @Volatile
    private var controller: HealthController? = null

    internal fun attach(value: HealthController) {
        controller = value
    }

    internal fun detach(value: HealthController) {
        if (controller === value) controller = null
    }

    suspend fun checkCurrent(service: BelkaManualService): BelkaManualResult =
        withContext(Dispatchers.IO) {
            val active = controller
                ?: return@withContext unavailable(service)
            runCatching { active.manualCheckCurrent(service) }
                .getOrElse {
                    BelkaManualResult(
                        service = service,
                        message = "Ошибка проверки: ${it.message ?: "неизвестная ошибка"}",
                    )
                }
        }

    suspend fun findAndSwitch(service: BelkaManualService): BelkaManualResult =
        withContext(Dispatchers.IO) {
            val active = controller
                ?: return@withContext unavailable(service)
            runCatching { active.manualFindAndSwitch(service) }
                .getOrElse {
                    BelkaManualResult(
                        service = service,
                        message = "Ошибка поиска сервера: ${it.message ?: "неизвестная ошибка"}",
                    )
                }
        }

    private fun unavailable(service: BelkaManualService) =
        BelkaManualResult(
            service = service,
            message = "Smart VPN ещё не готов. Убедитесь, что VPN запущен.",
        )
}
