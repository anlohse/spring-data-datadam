param([switch]$Background, [switch]$IntegrationTests, [switch]$BuildDatadam, [switch]$Restart)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
$previousMavenOptions = $env:MAVEN_OPTS
try {
    $jar = Join-Path $projectRoot 'datadam-demo/target/datadam-demo-0.1.0-SNAPSHOT.jar'
    $pidFile = Join-Path $projectRoot 'datadam-demo/target/demo.pid'
    if (Test-Path -LiteralPath $pidFile) {
        $demoProcessId = [int](Get-Content -LiteralPath $pidFile)
        $running = Get-CimInstance Win32_Process -Filter "ProcessId = $demoProcessId"
        if ($running -and $running.Name -eq 'java.exe' -and $running.CommandLine.IndexOf($jar, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
            if (-not $Restart) { throw 'The demo is already running. Use -Restart to rebuild and restart it.' }
            Stop-Process -Id $demoProcessId
            Wait-Process -Id $demoProcessId -ErrorAction SilentlyContinue
        }
    }
    # Trust the system CAs, including a locally managed HTTPS inspection certificate.
    $env:MAVEN_OPTS = "$previousMavenOptions -Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE"
    if ($BuildDatadam) { docker compose up -d --build } else { docker compose up -d --no-build }
    if ($LASTEXITCODE -ne 0) { throw 'Could not start Datadam. Start Docker Desktop first; use -BuildDatadam if the image is missing.' }
    $mavenArgs = @('-B', '-ntp', 'install')
    if ($IntegrationTests) { $mavenArgs += '-Pintegration' }
    & mvn @mavenArgs
    if ($LASTEXITCODE -ne 0) { throw 'Maven build failed' }
    $previousAdminPassword = $env:DATADAM_ADMIN_PASSWORD
    try {
        if (-not $env:DATADAM_ADMIN_PASSWORD) { $env:DATADAM_ADMIN_PASSWORD = 'local-schema-password' }
        & java '-Dloader.main=io.datadam.demo.SchemaApplication' -cp $jar org.springframework.boot.loader.launch.PropertiesLauncher
        if ($LASTEXITCODE -ne 0) { throw 'Schema initialization failed' }
    } finally { $env:DATADAM_ADMIN_PASSWORD = $previousAdminPassword }
    if ($Background) {
        $output = Join-Path $projectRoot 'datadam-demo/target/demo.log'
        $errors = Join-Path $projectRoot 'datadam-demo/target/demo-error.log'
        $process = Start-Process -FilePath (Get-Command java).Source -ArgumentList @('-jar', ('"' + $jar + '"')) -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput $output -RedirectStandardError $errors -PassThru
        $process.Id | Set-Content (Join-Path $projectRoot 'datadam-demo/target/demo.pid')
        Write-Host "Demo PID $($process.Id); http://localhost:8080; log: $output"
    } else { & java -jar $jar }
} finally {
    $env:MAVEN_OPTS = $previousMavenOptions
    Pop-Location
}
