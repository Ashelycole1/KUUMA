# Sets up Sunbird's Sunflower-14B in Ollama on this PC. Safe to run again: downloads resume.
#
#   powershell -ExecutionPolicy Bypass -File ai\local\sunflower\setup-sunflower.ps1 [-Quant q5_k_m]
#
# Weights go to %USERPROFILE%\kuuma-models\sunflower (outside the repo; never commit them).
# The model is public: no Hugging Face account or token is needed.
param(
    [ValidateSet("q4_k_m", "q5_k_m")] [string] $Quant = "q4_k_m",
    [string] $ModelDir = (Join-Path $env:USERPROFILE "kuuma-models\sunflower")
)
$ErrorActionPreference = "Stop"
$tag = if ($Quant -eq "q4_k_m") { "sunflower-14b:q4" } else { "sunflower-14b:q5" }
$file = "sunflower-14B-$Quant.gguf"
$url = "https://huggingface.co/Sunbird/Sunflower-14B-GGUF/resolve/main/$file"

Write-Host "1/4 Ollama"
$ollama = Get-Command ollama -ErrorAction SilentlyContinue
if (-not $ollama) {
    $candidate = Join-Path $env:LOCALAPPDATA "Programs\Ollama\ollama.exe"
    if (Test-Path $candidate) { $ollama = Get-Item $candidate } else {
        Write-Host "   Installing Ollama with winget..."
        winget install --id Ollama.Ollama -e --accept-package-agreements --accept-source-agreements --silent
        $ollama = Get-Item $candidate
    }
}
$exe = if ($ollama.Source) { $ollama.Source } else { $ollama.FullName }
& $exe --version
# Keep the model loaded between requests while working (read by Ollama, not by Kuuma).
[Environment]::SetEnvironmentVariable("OLLAMA_KEEP_ALIVE", "30m", "User")

Write-Host "2/4 Download $file (resumes if stopped)"
New-Item -ItemType Directory -Force $ModelDir | Out-Null
$gguf = Join-Path $ModelDir $file
& curl.exe -L -C - --retry 5 --retry-delay 5 -o $gguf $url
if ($LASTEXITCODE -ne 0) { throw "download failed; run the script again to resume" }

Write-Host "3/4 Build the Ollama model $tag"
$modelfile = Join-Path $ModelDir "Modelfile.$Quant"
(Get-Content (Join-Path $PSScriptRoot "Modelfile.template") -Raw).Replace("{{GGUF}}", $gguf) |
    Set-Content -Encoding ascii $modelfile
& $exe create $tag -f $modelfile
if ($LASTEXITCODE -ne 0) { throw "ollama create failed (is Ollama running? Start it from the Start menu)" }

Write-Host "4/4 Test question"
& $exe run $tag "Translate from Luganda to English: Webale nnyo."
Write-Host ""
Write-Host "Done. Set KUUMA_AI_MODE=sunflower and KUUMA_SUNFLOWER_MODEL=$tag in .env, then run:"
Write-Host "   uv run python ai\local\sunflower\smoke_test.py"
