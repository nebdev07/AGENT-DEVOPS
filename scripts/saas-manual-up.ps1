# Démarre la pile SaaS pour tests manuels (runner séparé + cibles privées).
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..\infrastructure\saas")

if (-not (Test-Path "runner.key")) {
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out runner.key
    openssl pkey -in runner.key -pubout -out runner.pub
    Write-Host "Clés runner.key / runner.pub générées."
}
if (-not (Test-Path ".env.manual")) {
    Copy-Item ".env.manual.example" ".env.manual"
    $mk = [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Maximum 256 } | ForEach-Object { [byte]$_ }))
    $tok = -join ((1..64) | ForEach-Object { "{0:x}" -f (Get-Random -Maximum 16) })
    $pub = (Get-Content "runner.pub" -Raw).Trim()
    $envText = Get-Content ".env.manual" -Raw
    $envText = $envText -replace '^AUTOPILOT_MASTER_KEY=$', "AUTOPILOT_MASTER_KEY=$mk"
    $envText = $envText -replace '^AUTOPILOT_RUNNER_TOKEN=.*$', "AUTOPILOT_RUNNER_TOKEN=$tok"
    $envText = $envText -replace '(?s)^AUTOPILOT_RUNNER_PUBLIC_KEY=.*?(?=\r?\n[A-Z]|\r?\n*$)', "AUTOPILOT_RUNNER_PUBLIC_KEY=`"$pub`""
    Set-Content ".env.manual" $envText
    Write-Host "Fichier .env.manual créé. Relance le script."
    exit 1
}

docker compose -f docker-compose.yml -f compose.manual.yml --env-file .env.manual up -d --build
$port = (Select-String -Path .env.manual -Pattern '^AUTOPILOT_HTTP_PORT=').Line.Split('=')[1]
Write-Host "SaaS manuel : http://127.0.0.1:$port/  — profil saas-manual, runner remote."
