param([Parameter(Mandatory)][string]$Version, [switch]$Sign)
$ErrorActionPreference = 'Stop'
if ($Version -notmatch '^\d+\.\d+\.\d+(-rc\.[1-9]\d*)?$') { throw 'Use a release or RC version without SNAPSHOT, for example 0.1.0-rc.1' }
if ($Sign -and -not $env:MAVEN_GPG_KEY) { throw 'Set MAVEN_GPG_KEY outside the checkout to sign the candidate' }
$projectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'release-tools.ps1')
. (Join-Path $PSScriptRoot 'verification-tools.ps1')
$run = Join-Path $projectRoot ('target/releases/' + $Version + '-' + [guid]::NewGuid().ToString('N'))
$source = Join-Path $run 'source'
$bundle = Join-Path $run 'bundle'
[void](New-Item -ItemType Directory -Path $bundle -Force)
$previousMaven = $env:MAVEN_OPTS
$commit = (& git -C $projectRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot identify source commit' }
$dirty = @(& git -C $projectRoot status --porcelain).Count -gt 0
Copy-DatadamReleaseSource $projectRoot $source
$poms = @('pom.xml','spring-data-datadam/pom.xml','datadam-spring-boot-autoconfigure/pom.xml','datadam-spring-boot-starter/pom.xml','datadam-demo/pom.xml')
foreach ($relative in $poms) {
    $path = Join-Path $source $relative
    [xml]$pom = Get-Content -Raw -LiteralPath $path
    if ($relative -eq 'pom.xml') { $pom.project.version = $Version; $pom.project.scm.tag = 'v' + $Version }
    else { $pom.project.parent.version = $Version }
    $pom.Save($path)
}
Push-Location $source
try {
    $env:MAVEN_OPTS = Get-DatadamMavenOptions $previousMaven
    $arguments = @('-B','-ntp','-pl','datadam-spring-boot-starter','-am','install')
    if ($Sign) { $arguments += '-Prelease-sign' }
    & mvn @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Candidate build failed' }
    & (Join-Path $source 'scripts/verify-library-artifacts.ps1') -LibraryVersion $Version
    & (Join-Path $source 'scripts/smoke-release.ps1') -Version $Version
    $entries = @()
    foreach ($module in @('datadam-spring-data-parent','spring-data-datadam','datadam-spring-boot-autoconfigure','datadam-spring-boot-starter')) {
        $directory = if ($module -eq 'datadam-spring-data-parent') { $source } else { Join-Path $source $module }
        $destination = Join-Path $bundle $module
        [void](New-Item -ItemType Directory -Path $destination)
        Copy-Item -LiteralPath (Join-Path $directory 'pom.xml') -Destination (Join-Path $destination "$module-$Version.pom")
        if ($module -ne 'datadam-spring-data-parent') {
            Get-ChildItem -LiteralPath (Join-Path $directory 'target') -Filter '*.jar' -File | Copy-Item -Destination $destination
        }
        if ($Sign) {
            $signatures = @(Get-ChildItem -LiteralPath (Join-Path $directory 'target') -Filter '*.asc' -File)
            if ($signatures.Count -eq 0) { throw "Missing signatures for $module" }
            foreach ($signature in $signatures) {
                $signatureName = if ($signature.Name -eq 'pom.xml.asc') { "$module-$Version.pom.asc" } else { $signature.Name }
                Copy-Item -LiteralPath $signature.FullName -Destination (Join-Path $destination $signatureName)
            }
        }
    }
    foreach ($file in (Get-ChildItem -LiteralPath $bundle -Recurse -File)) {
        $entries += [ordered]@{ file=[IO.Path]::GetRelativePath($bundle,$file.FullName).Replace('\','/'); sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
    }
    Copy-Item -LiteralPath (Join-Path $source 'examples/consumer-boot/target/verification.json') -Destination (Join-Path $run 'consumer-verification.json')
    [ordered]@{ groupId='com.anlohse.datadam'; version=$Version; status='PREPARED'; signed=[bool]$Sign; published=$false; sourceCommit=$commit;
        dirtySource=$dirty; sourceTag=('v'+$Version); destination='https://central.sonatype.com';
        files=$entries } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $run 'manifest.json') -Encoding utf8
    & (Join-Path $source 'scripts/new-central-bundle.ps1') -Manifest (Join-Path $run 'manifest.json') -RequireSignatures:$Sign
    Write-Host "RELEASE_PREPARED $Version; review $run; nothing was published"
} finally { $env:MAVEN_OPTS = $previousMaven; Pop-Location }
