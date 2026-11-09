param([string]$LibraryVersion = '0.1.0-SNAPSHOT', [switch]$Install)
$ErrorActionPreference = 'Stop'
if ($LibraryVersion -notmatch '^[A-Za-z0-9_.-]+$') { throw 'Invalid library version' }
$projectRoot = Split-Path -Parent $PSScriptRoot
$consumer = Join-Path $projectRoot 'examples/consumer-boot'
$previousMaven = $env:MAVEN_OPTS
. (Join-Path $PSScriptRoot 'verification-tools.ps1')
$savedEnvironment = @{}
foreach ($name in @('DATADAM_HOST','DATADAM_PORT','DATADAM_USERNAME','DATADAM_PASSWORD','CONSUMER_APP_PASSWORD','CONSUMER_SCHEMA_PASSWORD',
    'MAVEN_GPG_KEY','MAVEN_GPG_PASSPHRASE','CENTRAL_TOKEN_USERNAME','CENTRAL_TOKEN_PASSWORD')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
foreach ($name in @('MAVEN_GPG_KEY','MAVEN_GPG_PASSPHRASE','CENTRAL_TOKEN_USERNAME','CENTRAL_TOKEN_PASSWORD')) {
    [Environment]::SetEnvironmentVariable($name,$null,'Process')
}
$server = $null
Push-Location $projectRoot
try {
    $env:MAVEN_OPTS = Get-DatadamMavenOptions $previousMaven
    if ($Install) {
        if ($LibraryVersion -ne '0.1.0-SNAPSHOT') { throw '-Install installs the current checkout version, 0.1.0-SNAPSHOT' }
        & mvn -B -ntp -pl datadam-spring-boot-starter -am install
        if ($LASTEXITCODE -ne 0) { throw 'Library installation failed' }
    }
    # This is intentionally a separate Maven invocation: no reactor parent, modules or -am.
    & mvn -B -ntp -f (Join-Path $consumer 'pom.xml') "-Ddatadam.version=$LibraryVersion" clean package dependency:tree `
        '-Dincludes=com.anlohse.datadam:*' '-DoutputFile=target/datadam-dependencies.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Independent consumer build failed' }
    $jar = Join-Path $consumer 'target/consumer-boot-1.0.0-SNAPSHOT.jar'
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($jar)
    try {
        foreach ($module in @('spring-data-datadam','datadam-spring-boot-autoconfigure','datadam-spring-boot-starter')) {
            if (-not $archive.GetEntry("BOOT-INF/lib/$module-$LibraryVersion.jar")) { throw "Installed library artifact missing from consumer: $module" }
        }
        if ($archive.Entries.FullName -match '^BOOT-INF/lib/(datadam-demo|spring-boot-actuator|micrometer-core)') {
            throw 'Demo or optional telemetry dependencies leaked into the basic consumer'
        }
    } finally { $archive.Dispose() }
    $server = Start-DatadamVerificationServer -Config (Join-Path $consumer 'docker/datadam.conf') `
        -NamePrefix datadam-consumer -Username consumer-app -Password local-consumer-app-password
    $env:DATADAM_HOST = '127.0.0.1'
    $env:DATADAM_PORT = [string]$server.Port
    $env:CONSUMER_APP_PASSWORD = 'local-consumer-app-password'
    $env:DATADAM_USERNAME = 'consumer-app'; $env:DATADAM_PASSWORD = 'local-consumer-app-password'
    $env:CONSUMER_SCHEMA_PASSWORD = 'local-consumer-schema-password'
    & java '-Dloader.main=example.datadam.SchemaApplication' -cp $jar org.springframework.boot.loader.launch.PropertiesLauncher
    if ($LASTEXITCODE -ne 0) { throw 'Consumer schema preparation failed' }
    $env:CONSUMER_SCHEMA_PASSWORD = $null
    & java "-Dconsumer.expected-version=$LibraryVersion" -jar $jar
    if ($LASTEXITCODE -ne 0) { throw 'Consumer CRUD/query verification failed' }
    [ordered]@{ groupId='com.anlohse.datadam'; libraryVersion=$LibraryVersion; datadamRevision=$server.Revision; datadamImage=$server.Image; health='UP';
        status='PASSED'; artifactSource='Installed Maven artifacts; independent POM and executable JAR';
        operations=@('startup','health PING','runtime artifact version','CRUD','IDX pagination','FT projection','UNIQUE rejection','optimistic locking') } |
        ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $consumer 'target/verification.json') -Encoding utf8
} finally {
    try { if ($server) { Stop-DatadamVerificationServer $server } }
    finally {
        foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name,$savedEnvironment[$name],'Process') }
        $env:MAVEN_OPTS = $previousMaven
        Pop-Location
    }
}
