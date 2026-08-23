#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/io/nekohasekai/sfa/bg/health/HealthController.kt")
text = path.read_text(encoding="utf-8")
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
    raise SystemExit("No changes made")
path.write_text(text, encoding="utf-8")
print("BelkaVPN manual checks Kotlin fix applied")
