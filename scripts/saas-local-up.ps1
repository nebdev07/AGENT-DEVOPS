# Démarre la pile SaaS locale (embedded + lab : cibles privées, MailHog optionnel).
# Prérequis : Docker, Java non requis (image build).
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..\infrastructure\saas")

if (-not (Test-Path ".env.local")) {
    Copy-Item ".env.local.example" ".env.local"
    $mk = [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Maximum 256 } | ForEach-Object { [byte]$_ }))
    (Get-Content ".env.local") -replace '^AUTOPILOT_MASTER_KEY=$', "AUTOPILOT_MASTER_KEY=$mk" | Set-Content ".env.local"
    Write-Host "Fichier .env.local créé (clé maîtresse générée). Relance le script."
    exit 1
}

docker compose -f docker-compose.yml -f compose.local.yml -f compose.lab.yml --env-file .env.local up -d --build
Write-Host "SaaS local : http://127.0.0.1:$((Select-String -Path .env.local -Pattern '^AUTOPILOT_HTTP_PORT=').Line.Split('=')[1])/"
Write-Host "Profil Spring : saas-local (runner embedded). Swagger : /api/v2/docs"
