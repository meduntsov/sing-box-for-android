"""BelkaVPN CI compatibility hooks.

Loaded through PYTHONPATH=tools in GitHub Actions. Only the legacy v7 fix
process is affected; all other build patchers see normal pathlib behavior.
"""

import sys
from pathlib import Path


if sys.argv and sys.argv[0].endswith("apply_belka_manual_checks_v7_fix.py"):
    _read_text = Path.read_text
    _write_text = Path.write_text

    _health_policy_selector = '''    private fun clashSelect(tag: String) {
        clashSelect(plan.selectorTag, tag)
    }

    private fun clashSelect(selectorTag: String, tag: String) {
        val encoded = URLEncoder.encode(selectorTag, "UTF-8").replace("+", "%20")
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
'''

    _legacy_selector = '''    private fun clashSelect(tag: String) {
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
'''

    _old_ai_call = "runCatching { clashSelect(selectorTag, target) }"
    _new_ai_call = "runCatching { clashSelectOn(selectorTag, target) }"
    _new_helper = "private fun clashSelectOn(selectorTag: String, tag: String)"

    def _belka_read_text(self: Path, *args, **kwargs):
        text = _read_text(self, *args, **kwargs)
        if self.name == "HealthController.kt":
            if _legacy_selector not in text and _health_policy_selector in text:
                text = text.replace(_health_policy_selector, _legacy_selector, 1)
                print("BelkaVPN v7 compatibility: normalized health-policy Clash selector")
        return text

    def _belka_write_text(self: Path, data: str, *args, **kwargs):
        if self.name == "HealthController.kt":
            if _new_helper in data and _old_ai_call in data:
                data = data.replace(_old_ai_call, _new_ai_call, 1)
                print("BelkaVPN v7 compatibility: rewired AI selector to clashSelectOn")
            elif _old_ai_call in data:
                raise RuntimeError(
                    "BelkaVPN v7 compatibility: AI selector still uses two-argument "
                    "clashSelect but clashSelectOn helper is missing"
                )
        return _write_text(self, data, *args, **kwargs)

    Path.read_text = _belka_read_text
    Path.write_text = _belka_write_text
