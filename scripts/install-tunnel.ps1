$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$destination=Join-Path $taskRoot '.tools/cloudflared.exe'
New-Item -ItemType Directory -Force (Split-Path $destination -Parent) | Out-Null
$release=Invoke-RestMethod 'https://api.github.com/repos/cloudflare/cloudflared/releases/latest' -Headers @{'User-Agent'='Moa-Setup'}
$asset=$release.assets | Where-Object name -eq 'cloudflared-windows-amd64.exe' | Select-Object -First 1
if(-not $asset -or $asset.digest -notmatch '^sha256:[a-f0-9]{64}$'){throw 'Official release does not include a SHA256 digest. Install cloudflared from the official download page.'}
Invoke-WebRequest -UseBasicParsing $asset.browser_download_url -OutFile "$destination.download"
$actual=(Get-FileHash -LiteralPath "$destination.download" -Algorithm SHA256).Hash.ToLowerInvariant()
if($actual -ne $asset.digest.Substring(7)){Remove-Item -LiteralPath "$destination.download";throw 'Cloudflared SHA256 mismatch.'}
Move-Item -LiteralPath "$destination.download" -Destination $destination -Force
Write-Output "Installed official cloudflared $($release.tag_name); SHA256 verified."
