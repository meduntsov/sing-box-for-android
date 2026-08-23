#!/usr/bin/env python3
from pathlib import Path

health = Path("app/src/main/java/io/nekohasekai/sfa/bg/health/HealthController.kt")
text = health.read_text(encoding="utf-8")
original = text

old = '''    private fun manualServiceProbe(
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
'''

new = '''    private fun manualServiceProbe(
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
'''

count = text.count(old)
if count != 1:
    raise SystemExit(f"manualServiceProbe fix: expected exactly 1 match, found {count}")
text = text.replace(old, new, 1)
if text == original:
    raise SystemExit("HealthController: no changes made")
health.write_text(text, encoding="utf-8")

card = Path("app/src/main/java/io/nekohasekai/sfa/compose/screen/dashboard/BelkaSmartStatusCard.kt")
card_text = card.read_text(encoding="utf-8")
card_original = card_text
card_old = '''import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
'''
card_new = '''import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
'''
count = card_text.count(card_old)
if count != 1:
    raise SystemExit(f"weight import fix: expected exactly 1 match, found {count}")
card_text = card_text.replace(card_old, card_new, 1)
if card_text == card_original:
    raise SystemExit("BelkaSmartStatusCard: no changes made")
card.write_text(card_text, encoding="utf-8")

print("BelkaVPN manual checks Kotlin fixes applied")
print("- block body for manualServiceProbe")
print("- remove invalid RowScope weight import")
