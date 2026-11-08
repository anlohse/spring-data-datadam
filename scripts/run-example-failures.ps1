# Opt-in: fault injection uses disposable containers and never interrupts the demo server.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$previousMaven = $env:MAVEN_OPTS
Push-Location $projectRoot
try {
    $env:MAVEN_OPTS = "$previousMaven -Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE"
    & mvn -B -ntp -pl datadam-demo '-Pintegration,example-failures' test-compile failsafe:integration-test failsafe:verify `
        '-Dit.test=TransportFailuresIT,TlsSecurityIT,AclSecurityIT'
    if ($LASTEXITCODE -ne 0) { throw 'Example 7.2.1 failed; inspect datadam-demo/target/example-failures/failsafe-reports' }
    Write-Host 'EXAMPLE_RESULT 7.2.1 PASSED: 15 transport, ACL and TLS checks; no automatic write retry.'
} finally {
    $env:MAVEN_OPTS = $previousMaven
    Pop-Location
}
