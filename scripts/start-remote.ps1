param(
  [string]$PagesOrigin='https://parkcheolhan.github.io',
  [int]$Port=8080,
  [switch]$Build,
  [switch]$NoTunnel,
  [string]$LoginId='admin',
  [securestring]$LoginPassword
)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$backend=Join-Path $taskRoot 'backend'
$localDir=Join-Path $taskRoot '.local-remote'
New-Item -ItemType Directory -Force $localDir | Out-Null
$origin=[Uri]$PagesOrigin
if($origin.Scheme -ne 'https' -or $origin.AbsolutePath -ne '/' -or $origin.Query -or $origin.Fragment -or $origin.UserInfo -or $PagesOrigin.Contains('*')){throw 'PagesOrigin must be an exact HTTPS origin without a repository path.'}
$PagesOrigin=$origin.GetLeftPart([UriPartial]::Authority)
if(Test-Path (Join-Path $localDir 'processes.json')){throw 'Run scripts/stop-remote.ps1 before starting another instance.'}
if(-not $env:JAVA_HOME){
  $jdk=Get-ChildItem (Join-Path $env:USERPROFILE '.jdks') -Directory -ErrorAction SilentlyContinue | Where-Object {Test-Path (Join-Path $_.FullName 'bin/java.exe')} | Select-Object -First 1
  if($jdk){$env:JAVA_HOME=$jdk.FullName}
}
$java=if($env:JAVA_HOME){Join-Path $env:JAVA_HOME 'bin/java.exe'}else{(Get-Command java -ErrorAction Stop).Source}
$jar=Join-Path $backend 'target/meeting-hub-0.1.0.jar'
if($Build -or -not(Test-Path $jar)){
  Push-Location $backend
  try{& ./mvnw.cmd '-DskipTests' package;if($LASTEXITCODE -ne 0){throw 'Backend build failed.'}}finally{Pop-Location}
}
$tokenFile=Join-Path $localDir 'access-token.txt'
if(-not(Test-Path $tokenFile)){
  $bytes=New-Object byte[] 32
  $rng=[Security.Cryptography.RandomNumberGenerator]::Create()
  try{$rng.GetBytes($bytes)}finally{$rng.Dispose()}
  [IO.File]::WriteAllText($tokenFile,([BitConverter]::ToString($bytes).Replace('-','').ToLowerInvariant()))
}
$token=[IO.File]::ReadAllText($tokenFile).Trim()
if($token.Length -lt 32){throw 'Invalid access token file. Use a random token with at least 32 characters.'}
$cloudflared=Join-Path $taskRoot '.tools/cloudflared.exe'
if(-not $NoTunnel -and -not(Test-Path $cloudflared)){& (Join-Path $PSScriptRoot 'install-tunnel.ps1')}
$env:APP_REMOTE_TOKEN=$token
$env:APP_REMOTE_ALLOWED_ORIGINS=$PagesOrigin
$loginFile=Join-Path $localDir 'login-credentials.json'
if(-not(Test-Path -LiteralPath $loginFile) -and -not $LoginPassword){$LoginPassword=Read-Host "Login token for $LoginId" -AsSecureString}
if($LoginPassword){@{id=$LoginId;encryptedPassword=(ConvertFrom-SecureString $LoginPassword)} | ConvertTo-Json | Set-Content -LiteralPath $loginFile -Encoding UTF8}
if(Test-Path -LiteralPath $loginFile){
  $login=Get-Content -LiteralPath $loginFile -Raw | ConvertFrom-Json
  $secure=ConvertTo-SecureString $login.encryptedPassword
  $pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
  try{$env:APP_REMOTE_LOGIN_ID=$login.id;$env:APP_REMOTE_LOGIN_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)}
}
$env:SERVER_ADDRESS='127.0.0.1'
$env:SERVER_PORT="$Port"
$processes=@()
function Save-Processes {
  ConvertTo-Json -InputObject @($script:processes) | Set-Content (Join-Path $localDir 'processes.json') -Encoding UTF8
}
try{
  $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$Port)
  try{$listener.Start()}finally{$listener.Stop()}
  $server=Start-Process -FilePath $java -ArgumentList @('-jar',('"'+$jar+'"'),'--spring.profiles.active=demo,remote') -WorkingDirectory $backend -WindowStyle Hidden -RedirectStandardOutput (Join-Path $localDir 'server.log') -RedirectStandardError (Join-Path $localDir 'server-error.log') -PassThru
  $processes+=@{id=$server.Id;path=$java;started=$server.StartTime.ToUniversalTime().ToString('o')};Save-Processes
  $ready=$false
  for($i=0;$i -lt 60;$i++){
    if($server.HasExited){throw 'Server stopped. Check .local-remote/server.log and server-error.log.'}
    try{$null=Invoke-RestMethod "http://127.0.0.1:$Port/api/meetings" -Headers @{Authorization="Bearer $token"} -TimeoutSec 2;$ready=$true;break}catch{Start-Sleep -Seconds 1}
  }
  if(-not $ready){throw 'Server startup timed out. Check .local-remote/server.log.'}
  $apiUrl="http://127.0.0.1:$Port"
  if(-not $NoTunnel){
    $tunnelLog=Join-Path $localDir 'tunnel.log'
    $tunnel=Start-Process -FilePath $cloudflared -ArgumentList @('tunnel','--no-autoupdate','--protocol','http2','--url',$apiUrl) -WorkingDirectory $taskRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $localDir 'tunnel-output.log') -RedirectStandardError $tunnelLog -PassThru
    $processes+=@{id=$tunnel.Id;path=$cloudflared;started=$tunnel.StartTime.ToUniversalTime().ToString('o')};Save-Processes
    $found=$false
    for($i=0;$i -lt 45;$i++){
      if($tunnel.HasExited){throw 'Tunnel stopped. Check .local-remote/tunnel.log.'}
      $logText=' ' + (Get-Content -LiteralPath $tunnelLog -Raw -ErrorAction SilentlyContinue)
      $match=[regex]::Match($logText,'https://[a-z0-9-]+\.trycloudflare\.com')
      if($match.Success){$apiUrl=$match.Value;$found=$true;break};Start-Sleep -Seconds 1
    }
    if(-not $found){throw 'Tunnel address unavailable. Check .local-remote/tunnel.log.'}
  }
  @{apiUrl=$apiUrl;pagesOrigin=$PagesOrigin;pagesUrl=($PagesOrigin+'/Ai_MITTING_HUB/');port=$Port} | ConvertTo-Json | Set-Content (Join-Path $localDir 'connection.json') -Encoding UTF8
  Write-Output "Server ready: $apiUrl"
  Write-Output "Allowed Pages origin: $PagesOrigin"
  Write-Output "Access token is in $tokenFile (keep private; do not upload)."
}catch{
  Write-Warning $_.Exception.Message
  & (Join-Path $PSScriptRoot 'stop-remote.ps1')
  throw
}finally{
  Remove-Item Env:APP_REMOTE_TOKEN -ErrorAction SilentlyContinue
  Remove-Item Env:APP_REMOTE_LOGIN_ID,Env:APP_REMOTE_LOGIN_PASSWORD -ErrorAction SilentlyContinue
}
