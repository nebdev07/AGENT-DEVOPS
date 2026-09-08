#Requires -Version 5.1
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$lab = Join-Path $root "infrastructure\lab"
$keys = Join-Path $lab "keys"

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Host "Docker n'est pas installe. Sans Docker/WSL, utilise la console en mode laboratoire (fixture) :"
    Write-Host "  mvn -q spring-boot:run   puis   http://127.0.0.1:8088/"
    Write-Host "Pour un SSH Ubuntu reel en local : installer Docker Desktop, puis relancer ce script."
    exit 1
}

New-Item -ItemType Directory -Force -Path $keys | Out-Null
$key = Join-Path $keys "lab"
if (-not (Test-Path $key)) {
    ssh-keygen -t ed25519 -f $key -N '""' -C "vps-autopilot-lab" | Out-Host
}

Set-Location $lab
docker compose up -d --build
Write-Host "Lab SSH : lab@127.0.0.1:2222  cle=$key"
