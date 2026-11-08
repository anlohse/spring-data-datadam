param([string]$Documents = '1000,10000', [int]$Samples = 50, [int]$Warmups = 10)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
$previousMavenOptions = $env:MAVEN_OPTS
try {
    $env:MAVEN_OPTS = "$previousMavenOptions -Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE"
    & mvn -B -ntp -pl datadam-demo '-Pintegration,performance' test-compile failsafe:integration-test failsafe:verify '-Dit.test=NativePerformanceBenchmark' "-Ddatadam.benchmark.documents=$Documents" "-Ddatadam.benchmark.samples=$Samples" "-Ddatadam.benchmark.warmups=$Warmups"
    if ($LASTEXITCODE -ne 0) { throw 'Performance workload failed.' }
    Write-Host 'Results: datadam-demo/target/performance/results.csv and environment.json'
} finally { $env:MAVEN_OPTS = $previousMavenOptions; Pop-Location }
