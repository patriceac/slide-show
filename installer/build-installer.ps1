$ErrorActionPreference = 'Stop'

$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$artifactsDir = Join-Path $root 'artifacts\installer'
$payloadDir = Join-Path $artifactsDir 'payload'
$setupPublishDir = Join-Path $artifactsDir 'setup-publish'
$payloadZip = Join-Path $artifactsDir 'SlideShowPayload.zip'
$setupExe = Join-Path $artifactsDir 'SlideShowSetup.exe'

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,

        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$FilePath failed with exit code $LASTEXITCODE"
    }
}

New-Item -ItemType Directory -Force -Path $artifactsDir | Out-Null

foreach ($path in @($payloadDir, $setupPublishDir, $payloadZip, $setupExe)) {
    if (Test-Path -LiteralPath $path) {
        Remove-Item -LiteralPath $path -Recurse -Force
    }
}

Invoke-Checked dotnet @(
    'publish',
    (Join-Path $root 'SlideShow.csproj'),
    '--configuration',
    'Release',
    '--runtime',
    'win-x64',
    '--self-contained',
    'true',
    '--output',
    $payloadDir,
    '/p:PublishSingleFile=false',
    '/p:PublishReadyToRun=true'
)

Compress-Archive -Path (Join-Path $payloadDir '*') -DestinationPath $payloadZip -Force

Invoke-Checked dotnet @(
    'publish',
    (Join-Path $PSScriptRoot 'SlideShow.Installer.csproj'),
    '--configuration',
    'Release',
    '--runtime',
    'win-x64',
    '--self-contained',
    'true',
    '--output',
    $setupPublishDir,
    "/p:PayloadZip=$payloadZip"
)

Copy-Item -LiteralPath (Join-Path $setupPublishDir 'SlideShowSetup.exe') -Destination $setupExe -Force

Write-Host "Installer created: $setupExe"
