#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/io/nekohasekai/sfa/bg/health/HealthController.kt")
text = path.read_text(encoding="utf-8")

old = "runCatching { clashSelect(selectorTag, target) }"
new = "runCatching { clashSelectOn(selectorTag, target) }"

count = text.count(old)
if count != 1:
    raise SystemExit(
        f"AI selector two-argument call: expected exactly 1 match, found {count}"
    )

if "private fun clashSelectOn(selectorTag: String, tag: String)" not in text:
    raise SystemExit("clashSelectOn helper is missing after v7 fix")

text = text.replace(old, new, 1)
path.write_text(text, encoding="utf-8")

print("BelkaVPN v7 AI selector compatibility applied:")
print("- syncAiSelector now calls clashSelectOn(selectorTag, target)")
print("- one-argument clashSelect(tag) remains for the main selector")
