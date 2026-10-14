#!/usr/bin/env sh
# Starts the Webmatica viewer (Linux/macOS) and opens it in the browser.
# Custom folder: ./start-viewer.sh --recording ~/.minecraft/webmatica_recording
cd "$(dirname "$0")" || exit 1
PY=python3
command -v $PY >/dev/null 2>&1 || PY=python
[ -x .venv/bin/python ] && PY=.venv/bin/python
if ! $PY -c "import numpy, PIL" 2>/dev/null; then
  # "externally managed" Python (PEP 668) refuses --user: fall back to a private .venv
  $PY -m pip install --user -r requirements.txt || {
    $PY -m venv .venv && .venv/bin/python -m pip install -r requirements.txt && PY=.venv/bin/python
  } || exit 1
fi
( sleep 5; (command -v xdg-open >/dev/null && xdg-open http://127.0.0.1:8770) || open http://127.0.0.1:8770 ) >/dev/null 2>&1 &
exec $PY -m viewer "$@"
