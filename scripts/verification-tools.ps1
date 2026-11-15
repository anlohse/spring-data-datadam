function Get-DatadamMavenOptions([string]$Previous) {
    if ($IsWindows) { return "$Previous -Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE" }
    return $Previous
}

function Start-DatadamVerificationServer {
    param([string]$Config, [string]$NamePrefix, [string]$Username, [string]$Password)
    . (Join-Path $PSScriptRoot 'test-images.ps1')
    $images = Get-DatadamTestImages
    $image = Get-DatadamTestImage $images
    $name = $NamePrefix + '-' + [guid]::NewGuid().ToString('N')
    $created = $false
    try {
        # Copy config through the Docker API: works on Windows, Linux and remote Linux Docker engines.
        & docker create --name $name --publish '127.0.0.1::6379' --tmpfs '/var/lib/datadam:rw,mode=1777' `
            --entrypoint datadam $image.Id /tmp/verification.conf | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not create disposable Datadam' }
        $created = $true
        & docker cp (Resolve-Path -LiteralPath $Config).Path "${name}:/tmp/verification.conf"
        if ($LASTEXITCODE -ne 0) { throw 'Could not copy server configuration' }
        & docker start $name | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not start disposable Datadam' }
        $binding = & docker port $name '6379/tcp'
        if ($LASTEXITCODE -ne 0 -or -not $binding -or $binding.Trim() -notmatch '^127\.0\.0\.1:(\d+)$') { throw 'Unexpected container port binding' }
        $port = [int]$Matches[1]
        $ready = $false
        $deadline = [DateTime]::UtcNow.AddSeconds(30)
        while (-not $ready -and [DateTime]::UtcNow -lt $deadline) {
            $client = [Net.Sockets.TcpClient]::new()
            try {
                $client.Connect('127.0.0.1', $port)
                $client.ReceiveTimeout = 1000
                $stream = $client.GetStream()
                $reader = [IO.StreamReader]::new($stream)
                foreach ($arguments in @(@('AUTH',$Username,$Password), @('PING'))) {
                    $request = '*' + $arguments.Count + "`r`n"
                    foreach ($argument in $arguments) { $request += '$' + [Text.Encoding]::UTF8.GetByteCount($argument) + "`r`n" + $argument + "`r`n" }
                    $bytes = [Text.Encoding]::UTF8.GetBytes($request)
                    $stream.Write($bytes,0,$bytes.Length)
                    if ($reader.ReadLine() -notin @('+OK','+PONG')) { throw 'Server not ready' }
                }
                $ready = $true
            } catch { Start-Sleep -Milliseconds 100 }
            finally { $client.Dispose() }
        }
        if (-not $ready) { throw 'Datadam readiness timed out' }
        return [pscustomobject]@{ Name=$name; Port=$port; Image=$image.Id; Revision=$images.DATADAM_REVISION }
    } catch {
        if ($created) { & docker rm -f -v $name | Out-Null }
        throw
    }
}

function Stop-DatadamVerificationServer($Server) {
    & docker rm -f -v $Server.Name | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not remove owned verification container' }
}
