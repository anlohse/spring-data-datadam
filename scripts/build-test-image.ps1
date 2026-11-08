param([string]$Source = $env:DATADAM_SOURCE)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'test-images.ps1')
$images = Get-DatadamTestImages
if (-not $Source) { $Source = 'D:/cpp/workspaces/datadam' }
$projectRoot = Split-Path -Parent $PSScriptRoot
$buildDirectory = Join-Path $projectRoot ('target/datadam-source-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $buildDirectory -Force | Out-Null
$archive = Join-Path $buildDirectory 'source.tar'
& git -C $Source archive --format=tar ('--output=' + $archive) $images.DATADAM_REVISION
if ($LASTEXITCODE -ne 0) { throw 'Pinned Datadam revision is not available in the source repository.' }
& tar -xf $archive -C $buildDirectory
if ($LASTEXITCODE -ne 0) { throw 'Could not extract the pinned Datadam source.' }
# Build only the archived revision. Uncommitted changes in the C++ checkout are never included.
Remove-Item -LiteralPath $archive
& docker build --label ('org.opencontainers.image.revision=' + $images.DATADAM_REVISION) --label 'io.datadam.spring.test-image=true' -t $images.DATADAM_IMAGE $buildDirectory
if ($LASTEXITCODE -ne 0) { throw 'Pinned Datadam image build failed.' }
& docker image inspect $images.DATADAM_IMAGE --format '{{.Id}} {{index .Config.Labels "org.opencontainers.image.revision"}}'
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the built image.' }
