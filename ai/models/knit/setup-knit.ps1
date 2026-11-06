param([string]$LocalAssets = '')
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$target = Join-Path $repo 'packages\ai-runtime-android\src\main\assets\knit'
New-Item -ItemType Directory -Force -Path $target | Out-Null
$pin = '915e7021dd3df9563ff033e21f9b3acb977ac4f7'
$hashes = [ordered]@{
    'toxicity.tflite' = '5edb906c77e7b872f7e53eac15968da238f7b0fadd26e7b497ef22f72fac8a39'
    'tokenizer.json' = '77e18685566ddefc3267601b473a3c55aa6fa2735611e1eae67dee9a27e4d966'
    'labels.txt' = '76fd52d4d35136175281f28f96972e4e4db19e4dc4877f6335429ae97dea7b1c'
}
foreach ($name in $hashes.Keys) {
    $destination = Join-Path $target $name
    if ((Test-Path -LiteralPath $destination) -and (Get-FileHash -LiteralPath $destination).Hash.ToLowerInvariant() -eq $hashes[$name]) { continue }
    $partial = Join-Path $target "$([guid]::NewGuid()).partial"
    try {
        if ($LocalAssets) {
            Copy-Item -LiteralPath (Join-Path $LocalAssets $name) -Destination $partial
        } else {
            $origin = if ($name -eq 'toxicity.tflite') { 'https://media.githubusercontent.com/media' } else { 'https://raw.githubusercontent.com' }
            Invoke-WebRequest -Uri "$origin/getknit/knit/$pin/app/src/main/assets/moderation/$name" -OutFile $partial
        }
        if ((Get-FileHash -LiteralPath $partial).Hash.ToLowerInvariant() -ne $hashes[$name]) { throw "Checksum failed for $name" }
        Move-Item -LiteralPath $partial -Destination $destination -Force
    } finally { if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial } }
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'LICENSE.txt') -Destination (Join-Path $target 'LICENSE.txt') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'NOTICE.txt') -Destination (Join-Path $target 'NOTICE.txt') -Force
Write-Output 'Pinned English Knit assets verified. No messages or model inference were sent online.'
