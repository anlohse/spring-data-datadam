param([Parameter(Mandatory)][string]$Version)
$ErrorActionPreference = 'Stop'
if ($Version -notmatch '^\d+\.\d+\.\d+(-rc\.[1-9]\d*)?$') { throw 'Use a release or release-candidate version, for example 0.1.0-rc.1' }
& (Join-Path $PSScriptRoot 'verify-consumer.ps1') -LibraryVersion $Version
$report = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot '../examples/consumer-boot/target/verification.json') | ConvertFrom-Json
if ($report.libraryVersion -ne $Version -or $report.status -ne 'PASSED' -or $report.health -ne 'UP') { throw 'Candidate consumer smoke test failed' }
Write-Host "RELEASE_SMOKE_OK $Version startup, health, artifact versions, CRUD and native queries"
