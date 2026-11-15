function Get-DatadamTestImages {
    $imageSettings = @{}
    foreach ($line in (Get-Content -LiteralPath (Join-Path $PSScriptRoot '../config/test-images.properties'))) {
        if ($line -match '^([A-Z0-9_]+)=(.+)$') { $imageSettings[$Matches[1]] = $Matches[2].Trim() }
    }
    return $imageSettings
}

function Get-DatadamLocalImageName($Images) {
    return 'datadam:spring-data-' + $Images.DATADAM_REVISION.Substring(0,12)
}

function Get-DatadamTestImage($Images) {
    $reference = if ($env:DATADAM_IMAGE) { $env:DATADAM_IMAGE } else { $Images.DATADAM_IMAGE }
    $inspection = & docker image inspect $reference 2>$null
    if ($LASTEXITCODE -ne 0) {
        & docker pull $reference | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "Could not download Datadam image: $reference" }
        $inspection = & docker image inspect $reference
        if ($LASTEXITCODE -ne 0) { throw "Could not inspect Datadam image: $reference" }
    }
    $image = ($inspection | ConvertFrom-Json)[0]
    if ($image.Config.Labels.'org.opencontainers.image.revision' -ne $Images.DATADAM_REVISION) {
        throw "Datadam image revision mismatch: $reference"
    }
    return $image
}
