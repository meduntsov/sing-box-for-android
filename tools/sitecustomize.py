"""BelkaVPN CI compatibility hooks.

Python automatically imports sitecustomize from the script directory.  Only the
legacy v7 fix process is affected; all other build patchers see normal pathlib.
"""

import sys
from pathlib import Path


if sys.argv and sys.argv[0].endswith("apply_belka_manual_checks_v7_fix.py"):
    _read_text = Path.read_text

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

    def _belka_read_text(self: Path, *args, **kwargs):
        text = _read_text(self, *args, **kwargs)
        if self.name == "HealthController.kt":
            if _legacy_selector not in text and _health_policy_selector in text:
                text = text.replace(_health_policy_selector, _legacy_selector, 1)
                print("BelkaVPN v7 compatibility: normalized health-policy Clash selector")
        return text

    Path.read_text = _belka_read_text
