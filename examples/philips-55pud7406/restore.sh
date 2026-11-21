#!/usr/bin/env bash
# Undo everything on the Philips 55PUD7406: re-enable the debloated apps, bring back the stock
# Android TV home screen and make it the default again. The custom launcher stays installed but unused
# (remove it with: tools/venv/bin/python -I tools/tv.py pm uninstall com.mihir.tvhome).
cd "$(dirname "$0")/../.."
STOCK_LAUNCHER=com.google.android.tvlauncher/.MainActivity exec tools/restore.sh examples/philips-55pud7406/debloat.txt
