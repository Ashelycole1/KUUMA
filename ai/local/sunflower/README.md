# Sunflower on this PC

Sunbird AI's Sunflower-14B, served by Ollama on SURFACE-02 (RTX 3060 Laptop 6 GB, 32 GB RAM).
Only the AI service talks to it, through `packages/kuuma-llm`. Design: `Nilinde — Sunflower Integration Design.md`.

```powershell
powershell -ExecutionPolicy Bypass -File ai\local\sunflower\setup-sunflower.ps1      # Q4_K_M, 9.0 GB
uv run python ai\local\sunflower\smoke_test.py                                       # real answers + timings
```

Then in `.env`: `KUUMA_AI_MODE=sunflower`. Run the AI service on its own:

```powershell
$env:KUUMA_AI_MODE="sunflower"; uv run uvicorn kuuma_ai_service.app:app --port 8081
curl http://localhost:8081/healthz/model      # {"sunflower":"up", ...}
```

| File | What |
| --- | --- |
| `Modelfile.template` | Sunbird's Modelfile (system message, chat template, stop tokens); the script fills in the path |
| `setup-sunflower.ps1` | Installs Ollama (winget), downloads the GGUF to `%USERPROFILE%\kuuma-models\sunflower`, builds `sunflower-14b:q4` |
| `smoke_test.py` | Invented messages in lg / sw / nyn; prints answers and latency |

Weights never go in the repo (`*.gguf` is git-ignored). With 6 GB of VRAM, Ollama puts part of
the 14B model on the GPU and the rest on the CPU (`ollama ps` shows the split); expect seconds per answer.
