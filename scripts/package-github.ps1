$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$output=Join-Path $taskRoot 'github'
New-Item -ItemType Directory -Force $output | Out-Null
$files=@(
  'frontend/package.json','frontend/package-lock.json','frontend/tsconfig.json','frontend/vite.config.ts','frontend/index.html',
  'frontend/scripts/build-html.mjs','frontend/scripts/test-pages.mjs','frontend/scripts/test-html.mjs','frontend/playwright.config.ts',
  'backend/pom.xml','backend/mvnw','backend/mvnw.cmd','backend/.mvn/wrapper/maven-wrapper.properties',
  'scripts/setup-speech.ps1','scripts/setup-speech.sh','scripts/install-tunnel.ps1','scripts/start-remote.ps1','scripts/stop-remote.ps1','scripts/package-github.ps1','scripts/github-pages.yml',
  'docs/github-local-server.md','docs/transcript-review.md'
)
foreach($folder in @('frontend/src','frontend/e2e','backend/src')){
  $files+=Get-ChildItem -LiteralPath (Join-Path $taskRoot $folder) -Recurse -File | Where-Object {$_.FullName -notmatch '[\\/]__pycache__[\\/]' -and $_.Extension -notin @('.pyc','.key','.db','.log','.m4a','.mp4','.mp3','.wav','.webm')} | ForEach-Object {$_.FullName.Substring($taskRoot.Length+1).Replace('\','/')}
}
foreach($relative in $files){
  $source=Join-Path $taskRoot $relative
  if(-not(Test-Path -LiteralPath $source)){throw "Missing package source: $relative"}
  $target=Join-Path $output $relative
  New-Item -ItemType Directory -Force (Split-Path $target -Parent) | Out-Null
  Copy-Item -LiteralPath $source -Destination $target -Force
}
Copy-Item -LiteralPath (Join-Path $taskRoot 'frontend/pages/index.html') -Destination (Join-Path $output 'index.html') -Force
[IO.File]::WriteAllText((Join-Path $output '.nojekyll'),'')
New-Item -ItemType Directory -Force (Join-Path $output '.github/workflows') | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'github-pages.yml') -Destination (Join-Path $output '.github/workflows/pages.yml') -Force
Copy-Item -LiteralPath (Join-Path $taskRoot 'docs/github-local-server.md') -Destination (Join-Path $output 'README.md') -Force
@'
node_modules/
dist/
target/
data/
.tools/
.local-remote/
.idea/
.env
.env.*
!.env.example
*.log
*.key
*.db
*.mv.db
*.tsbuildinfo
__pycache__/
frontend/pages/
frontend/standalone/
test-results/
playwright-report/
frontend/e2e/fixtures/
'@ | Set-Content (Join-Path $output '.gitignore') -Encoding UTF8
$forbidden=Get-ChildItem -LiteralPath $output -Recurse -File -Force | Where-Object {$_.FullName -match '[\\/](data|\.local-remote|node_modules|roar-project|__pycache__)[\\/]' -or $_.Name -match '^\.env$|access-token|\.mv\.db$|\.key$|\.log$'}
if($forbidden){throw 'Unexpected private/runtime file in github output. Review the output folder before uploading.'}
Write-Output "GitHub upload folder ready: $output"
Write-Output 'Upload its contents as the repository root, including .github and .nojekyll.'
