#!/usr/bin/env sh
set -eu
speech_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
python3 -m venv "$speech_root/.tools/speech"
"$speech_root/.tools/speech/bin/python" -m pip install -r "$speech_root/backend/src/main/resources/speech/requirements.txt"
"$speech_root/.tools/speech/bin/python" "$speech_root/backend/src/main/resources/speech/transcribe.py" --prepare --model-dir "$speech_root/.tools/speech-models/small" --speaker-dir "$speech_root/.tools/speech-models/speakers"
echo 'Local speech transcription is ready. No API key is required.'
