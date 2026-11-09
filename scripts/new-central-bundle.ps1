param([Parameter(Mandatory)][string]$Manifest, [switch]$RequireSignatures)
$ErrorActionPreference = 'Stop'
$manifestPath = (Resolve-Path -LiteralPath $Manifest).Path
$report = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
if ($report.groupId -ne 'com.anlohse.datadam' -or $report.version -notmatch '^\d+\.\d+\.\d+(-rc\.[1-9]\d*)?$' -or $report.destination -ne 'https://central.sonatype.com') { throw 'Invalid Central candidate manifest' }
if ($RequireSignatures -and -not $report.signed) { throw 'A signed candidate is required' }
$run = Split-Path -Parent $manifestPath
$inputRoot = Join-Path $run 'bundle'
$repository = Join-Path $run 'central-repository'
if (Test-Path -LiteralPath $repository) { throw 'Central repository output already exists' }
[void](New-Item -ItemType Directory -Path $repository)
$payloadCount = 0
foreach ($module in @('datadam-spring-data-parent','spring-data-datadam','datadam-spring-boot-autoconfigure','datadam-spring-boot-starter')) {
    $names = @("$module-$($report.version).pom")
    if ($module -ne 'datadam-spring-data-parent') { $names += @("$module-$($report.version).jar","$module-$($report.version)-sources.jar","$module-$($report.version)-javadoc.jar") }
    $destination = Join-Path $repository "com/anlohse/datadam/$module/$($report.version)"
    [void](New-Item -ItemType Directory -Path $destination -Force)
    foreach ($name in $names) {
        $relative = "$module/$name"
        $entry = @($report.files | Where-Object { $_.file -eq $relative })
        if ($entry.Count -ne 1) { throw "Manifest does not identify payload: $relative" }
        $file = Join-Path $inputRoot $relative
        if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry[0].sha256) { throw "Candidate checksum mismatch: $relative" }
        if ($name.EndsWith('.pom')) {
            [xml]$pom = Get-Content -Raw -LiteralPath $file
            $group = if ($pom.project.groupId) { [string]$pom.project.groupId } else { [string]$pom.project.parent.groupId }
            $version = if ($pom.project.version) { [string]$pom.project.version } else { [string]$pom.project.parent.version }
            if ($group -ne $report.groupId -or $pom.project.artifactId -ne $module -or $version -ne $report.version) {
                throw "POM coordinates differ from candidate manifest: $relative"
            }
        }
        Copy-Item -LiteralPath $file -Destination $destination
        foreach ($algorithm in @('MD5','SHA1','SHA256','SHA512')) {
            $hash = (Get-FileHash -LiteralPath $file -Algorithm $algorithm).Hash.ToLowerInvariant()
            [IO.File]::WriteAllText((Join-Path $destination ($name+'.'+$algorithm.ToLowerInvariant())), $hash)
        }
        $signature = Join-Path $inputRoot ($relative+'.asc')
        if ($report.signed -or $RequireSignatures) {
            $signatureEntry = @($report.files | Where-Object { $_.file -eq ($relative+'.asc') })
            if ($signatureEntry.Count -ne 1 -or (Get-FileHash -LiteralPath $signature -Algorithm SHA256).Hash.ToLowerInvariant() -ne $signatureEntry[0].sha256) {
                throw "Missing or mismatched detached signature: $relative"
            }
            Copy-Item -LiteralPath $signature -Destination $destination
        }
        $payloadCount++
    }
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = Join-Path $run 'central-bundle.zip'
[IO.Compression.ZipFile]::CreateFromDirectory($repository,$zip)
[ordered]@{ groupId=$report.groupId; version=$report.version; destination=$report.destination; payloads=$payloadCount; signed=[bool]$report.signed;
    dirtySource=[bool]$report.dirtySource; sha256=(Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant();
    submitted=$false; portalValidation='NOT_RUN'; file='central-bundle.zip' } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $run 'central-bundle.json') -Encoding utf8
Write-Host "CENTRAL_BUNDLE_READY $zip; signed=$($report.signed); no upload performed"
