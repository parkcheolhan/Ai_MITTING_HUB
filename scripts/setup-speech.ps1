$ErrorActionPreference = 'Stop'
$speechRoot = Split-Path $PSScriptRoot -Parent
$speechPython = Join-Path $speechRoot '.tools/speech/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $speechPython)) {
    py -3.13 -m venv (Join-Path $speechRoot '.tools/speech')
    if ($LASTEXITCODE -ne 0) { throw 'Python 3.13 is required. Install Python 3.13 and retry.' }
}
& $speechPython -m pip install -r (Join-Path $speechRoot 'backend/src/main/resources/speech/requirements.txt')
if ($LASTEXITCODE -ne 0) { throw 'Speech engine installation failed.' }
& $speechPython (Join-Path $speechRoot 'backend/src/main/resources/speech/transcribe.py') --prepare --model-dir (Join-Path $speechRoot '.tools/speech-models/small') --speaker-dir (Join-Path $speechRoot '.tools/speech-models/speakers')
if ($LASTEXITCODE -ne 0) { throw 'Speech model download failed. Check network access and retry.' }
Write-Output 'Local speech transcription is ready. No API key is required.'
