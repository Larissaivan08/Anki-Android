# Downloads pinned upstream artifacts; no credentials or external voice service.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$repoRoot = Split-Path $PSScriptRoot -Parent
$cache = Join-Path $repoRoot '.gradle/meduerj-voice'
New-Item -ItemType Directory -Force $cache | Out-Null

function Get-VerifiedArtifact($Url, $Path, $Sha256) {
    if (!(Test-Path -LiteralPath $Path)) { Invoke-WebRequest $Url -OutFile $Path }
    if ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -ne $Sha256) {
        throw "Checksum mismatch: $Path. Delete this file and retry."
    }
}

$aar = Join-Path $cache 'sherpa-onnx-1.12.29.aar'
Get-VerifiedArtifact 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.29/sherpa-onnx-1.12.29.aar' $aar '2beeb891a6f07043a7993d9957fdd4d6a67ec9b8ccdb573cb9fe57c4834f3376'
$archive = Join-Path $cache 'kokoro-multi-lang-v1_0.tar.bz2'
Get-VerifiedArtifact 'https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2' $archive 'c5f7e2d2caf082bc1d20fb70334a61d99d20b484500aad32e7cf84c128ea3298'
& tar -xf $archive -C $cache
if ($LASTEXITCODE -ne 0) { throw 'Could not extract Kokoro model' }
$destination = Join-Path $repoRoot 'AnkiDroid/src/main/assets/neural'
$libs = Join-Path $repoRoot 'AnkiDroid/libs'
New-Item -ItemType Directory -Force $destination, $libs | Out-Null
Copy-Item -LiteralPath $aar -Destination $libs -Force
$source = Join-Path $cache 'kokoro-multi-lang-v1_0'
python (Join-Path $PSScriptRoot 'prepare-meduerj-ptbr.py') $source $destination
if ($LASTEXITCODE -ne 0) { throw 'Could not prepare Brazilian-only voice assets' }
Write-Output 'Kokoro pt-BR ready. Build the APK with gradlew :AnkiDroid:assemblePlayDebug.'
