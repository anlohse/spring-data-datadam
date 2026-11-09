param([string]$LibraryVersion = '0.1.0-SNAPSHOT')
$ErrorActionPreference = 'Stop'
if ($LibraryVersion -notmatch '^[A-Za-z0-9_.-]+$') { throw 'Invalid library version' }
$projectRoot = Split-Path -Parent $PSScriptRoot
Add-Type -AssemblyName System.IO.Compression.FileSystem
$artifacts = @()
foreach ($module in @('spring-data-datadam','datadam-spring-boot-autoconfigure','datadam-spring-boot-starter')) {
    $classifiers = @('','-sources','-javadoc')
    foreach ($classifier in $classifiers) {
        $file = Join-Path $projectRoot "$module/target/$module-$LibraryVersion$classifier.jar"
        if (-not (Test-Path -LiteralPath $file)) { throw "Missing artifact: $file" }
        $zip = [IO.Compression.ZipFile]::OpenRead($file)
        try {
            $license = $zip.GetEntry('META-INF/LICENSE')
            if (-not $license) { throw "Missing project license in $file" }
            $reader = [IO.StreamReader]::new($license.Open())
            try { if (-not $reader.ReadToEnd().StartsWith('MIT License')) { throw "Unexpected project license in $file" } }
            finally { $reader.Dispose() }
            if ($classifier -eq '') {
                if ($zip.Entries.FullName -match '^io/datadam/.*\.(class|lua)$') { throw 'Legacy Java packages remain in the JAR; rebuild from clean sources' }
                $metadata = $zip.GetEntry("META-INF/maven/com.anlohse.datadam/$module/pom.properties")
                if (-not $metadata) { throw "Missing Maven metadata for com.anlohse.datadam:$module" }
                $metadataReader = [IO.StreamReader]::new($metadata.Open())
                try {
                    if ($metadataReader.ReadToEnd() -notmatch '(?m)^groupId=com\.anlohse\.datadam\r?$') { throw 'Unexpected Maven groupId' }
                } finally { $metadataReader.Dispose() }
            }
            if ($module -eq 'spring-data-datadam' -and $classifier -eq '') {
                if ($zip.GetEntry('com/anlohse/datadam/spring/config/DatadamAutoConfiguration.class')) { throw 'Boot classes leaked into the core JAR' }
                if ($zip.GetEntry('META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports')) { throw 'Boot auto-configuration registration leaked into the core JAR' }
                if (-not $zip.GetEntry('com/anlohse/datadam/spring/save-versioned-json.lua')) { throw 'Missing runtime Lua resource' }
            }
            if ($module -eq 'datadam-spring-boot-autoconfigure' -and $classifier -eq '') {
                foreach ($entry in @('META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports','META-INF/spring-configuration-metadata.json')) {
                    if (-not $zip.GetEntry($entry)) { throw "Missing autoconfiguration metadata: $entry" }
                }
                $imports = [IO.StreamReader]::new($zip.GetEntry('META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports').Open())
                try { $registered = @($imports.ReadToEnd() -split '\r?\n' | Where-Object { $_ -and -not $_.StartsWith('#') }) }
                finally { $imports.Dispose() }
                $expected = @('com.anlohse.datadam.spring.config.DatadamAutoConfiguration','com.anlohse.datadam.spring.config.DatadamDiagnosticsAutoConfiguration')
                if (@(Compare-Object $expected $registered).Count) { throw 'Unexpected Boot autoconfiguration registration' }
                foreach ($type in $registered) {
                    if (-not $zip.GetEntry($type.Replace('.','/')+'.class')) { throw "Registered autoconfiguration class is missing: $type" }
                }
            }
            if ($classifier -eq '-javadoc' -and -not $zip.GetEntry('index.html')) { throw "Missing generated Javadoc in $file" }
        } finally { $zip.Dispose() }
        $artifacts += [ordered]@{ artifact=$module; classifier=$classifier.TrimStart('-'); file=(Split-Path -Leaf $file); sha256=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() }
    }
}
$report = Join-Path $projectRoot 'target/library-artifacts.json'
[void](New-Item -ItemType Directory -Path (Split-Path -Parent $report) -Force)
$artifacts | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $report -Encoding utf8
Write-Host "ARTIFACTS_OK: $($artifacts.Count) JARs; core/Boot split, MIT licenses, sources, Javadoc and Boot metadata verified."
