$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$stateFile=Join-Path $taskRoot '.local-remote/processes.json'
if(-not(Test-Path $stateFile)){Write-Output 'No managed remote processes.';return}
$entries=Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
$unmatched=$false
foreach($entry in $entries){
  $process=Get-Process -Id $entry.id -ErrorAction SilentlyContinue
  if($process){
    if([IO.Path]::GetFullPath($process.Path) -eq [IO.Path]::GetFullPath($entry.path) -and $process.StartTime.ToUniversalTime().ToString('o') -eq $entry.started){Stop-Process -InputObject $process -Force}
    else{$unmatched=$true;Write-Warning "Process $($entry.id) identity differs; it was not stopped."}
  }
}
if($unmatched){throw 'Process identity mismatch. State file preserved for review.'}
Remove-Item -LiteralPath $stateFile
Write-Output 'Managed remote server and tunnel stopped.'
