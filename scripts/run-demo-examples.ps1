param(
    [ValidateSet('all','0.1','1.1.1','1.2.1','1.3.1','2.1.1','3.1.1','3.2.1','4.1.1','4.2.1','5.1.1','5.2.1','6.1.1','6.2.1','7.1.1','7.2.1','7.3.1')]
    [string]$Scenario = 'all',
    [ValidateRange(1,1000)][int]$Count = 30
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $projectRoot 'datadam-demo/target/datadam-demo-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Run scripts/run-local.ps1 -Background first.' }
Push-Location $projectRoot
$previousAdmin = $env:DATADAM_ADMIN_PASSWORD
try {
    if ($Scenario -ne '7.2.1') {
        # Explicit opt-in to the local Docker development credentials; no remote defaults.
        if (-not $env:DATADAM_ADMIN_PASSWORD) { $env:DATADAM_ADMIN_PASSWORD = 'local-schema-password' }
        if ($Scenario -ne '6.1.1') {
            & java '-Dloader.main=io.datadam.demo.SchemaApplication' -cp $jar org.springframework.boot.loader.launch.PropertiesLauncher
            if ($LASTEXITCODE -ne 0) { throw 'Example schema initialization failed' }
        }
        & java '-Dexamples.local-credentials=true' "-Dexamples.scenario=$Scenario" "-Dexamples.volume=$Count" `
            '-Dloader.main=io.datadam.demo.examples.DemoExamplesApplication' -cp $jar org.springframework.boot.loader.launch.PropertiesLauncher
        if ($LASTEXITCODE -ne 0) { throw "Example $Scenario failed" }
    }
    if ($Scenario -in @('all','7.2.1')) { & (Join-Path $PSScriptRoot 'run-example-failures.ps1') }
} finally {
    $env:DATADAM_ADMIN_PASSWORD = $previousAdmin
    Pop-Location
}
