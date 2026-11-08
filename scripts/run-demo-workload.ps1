param([ValidateRange(1,1000)][int]$Count = 30, [switch]$Benchmark)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'run-demo-examples.ps1') -Scenario '7.3.1' -Count $Count
if ($Benchmark) { & (Join-Path $PSScriptRoot 'run-performance.ps1') }
