param([switch]$BuildDatadam, [string]$Source = $env:DATADAM_SOURCE)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'verification-tools.ps1')
. (Join-Path $PSScriptRoot 'test-images.ps1')
$images = Get-DatadamTestImages
$previousMaven = $env:MAVEN_OPTS
$saved = @{}
foreach ($name in @('DATADAM_HOST','DATADAM_PORT','DATADAM_USERNAME','DATADAM_PASSWORD','DATADAM_ADMIN_PASSWORD',
    'DATADAM_SCHEMA_PASSWORD','DATADAM_INDEX_OBSERVER_PASSWORD','DATADAM_INDEX_MIGRATOR_PASSWORD','DATADAM_INDEX_RETIRER_PASSWORD')) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name,'Process')
}
$server = $null
Push-Location $projectRoot
try {
    $env:MAVEN_OPTS = Get-DatadamMavenOptions $previousMaven
    if ($BuildDatadam) { & (Join-Path $PSScriptRoot 'build-test-image.ps1') -Source $Source }
    foreach ($image in @($images.DATADAM_TLS_JAVA_IMAGE,$images.JAVA17_IMAGE,$images.JAVA21_IMAGE,$images.JAVA25_IMAGE)) {
        & docker image inspect $image *> $null
        if ($LASTEXITCODE -ne 0) {
            & docker pull $image
            if ($LASTEXITCODE -ne 0) { throw 'Could not obtain pinned Java test image' }
        }
    }
    $server = Start-DatadamVerificationServer -Config (Join-Path $projectRoot 'docker/datadam.conf') `
        -NamePrefix datadam-ci -Username app -Password local-app-password
    $env:DATADAM_HOST = '127.0.0.1'; $env:DATADAM_PORT = [string]$server.Port
    $env:DATADAM_USERNAME = 'app'; $env:DATADAM_PASSWORD = 'local-app-password'
    $env:DATADAM_ADMIN_PASSWORD = 'local-schema-password'; $env:DATADAM_SCHEMA_PASSWORD = 'local-schema-password'
    $env:DATADAM_INDEX_OBSERVER_PASSWORD = 'local-index-observer-password'
    $env:DATADAM_INDEX_MIGRATOR_PASSWORD = 'local-index-migrator-password'
    $env:DATADAM_INDEX_RETIRER_PASSWORD = 'local-index-retirer-password'
    & mvn -B -ntp install -Pintegration
    if ($LASTEXITCODE -ne 0) { throw 'CI build or tests failed' }
    & (Join-Path $PSScriptRoot 'verify-library-artifacts.ps1')
    & (Join-Path $PSScriptRoot 'verify-consumer.ps1')
    [ordered]@{ status='PASSED'; datadamImage=$server.Image; datadamRevision=$server.Revision;
        java=(& java -version 2>&1 | Out-String).Trim(); includes=@('unit','integration','Java 17/21/25 matrix','TLS/ACL','artifacts','independent consumer') } |
        ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $projectRoot 'target/ci-verification.json') -Encoding utf8
} finally {
    try { if ($server) { Stop-DatadamVerificationServer $server } }
    finally {
        foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name,$saved[$name],'Process') }
        $env:MAVEN_OPTS = $previousMaven
        Pop-Location
    }
}
