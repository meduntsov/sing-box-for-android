#!/usr/bin/env python3

# Session-wide manual server selection is already applied by
# apply_belka_manual_checks_v7_fix.py.  This step is intentionally kept as a
# no-op so older workflow files remain compatible without applying the same
# state/UI/controller changes twice.
print("BelkaVPN manual server selector v8: already applied by v7_fix; duplicate patch skipped")
