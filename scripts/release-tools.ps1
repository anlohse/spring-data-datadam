function Copy-DatadamReleaseSource([string]$SourceRoot, [string]$Destination) {
    if (Test-Path -LiteralPath $Destination) { throw 'Source snapshot destination already exists' }
    [void](New-Item -ItemType Directory -Path $Destination)
    foreach ($name in @('pom.xml','LICENSE','README.md','ROADMAP.md','CHANGELOG.md')) {
        $file = Join-Path $SourceRoot $name
        if (Test-Path -LiteralPath $file) { Copy-Item -LiteralPath $file -Destination $Destination }
    }
    foreach ($directory in @('spring-data-datadam','datadam-spring-boot-autoconfigure','datadam-spring-boot-starter','datadam-demo','examples/consumer-boot','scripts','config','docker','docs')) {
        $base = Join-Path $SourceRoot $directory
        foreach ($file in (Get-ChildItem -LiteralPath $base -File -Recurse)) {
            $relative = [IO.Path]::GetRelativePath($base,$file.FullName)
            if ($relative -match '(^|[/\\])(target|\.git|\.idea|\.codex|\.agents)([/\\]|$)' -or
                $file.Name -eq '.env' -or $file.Name -eq 'settings.xml' -or $file.Extension -in @('.pem','.key','.p12','.jks','.asc')) { continue }
            $target = Join-Path (Join-Path $Destination $directory) $relative
            [void](New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force)
            Copy-Item -LiteralPath $file.FullName -Destination $target
        }
    }
}
