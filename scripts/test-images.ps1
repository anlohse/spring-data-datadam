function Get-DatadamTestImages {
    $imageSettings = @{}
    foreach ($line in (Get-Content -LiteralPath (Join-Path $PSScriptRoot '../config/test-images.properties'))) {
        if ($line -match '^([A-Z0-9_]+)=(.+)$') { $imageSettings[$Matches[1]] = $Matches[2].Trim() }
    }
    return $imageSettings
}
