# Orchestre la bascule prod depuis le poste opérateur (SSH).
#   .\scripts\saas-bascule-prod.ps1           # dry-run
#   .\scripts\saas-bascule-prod.ps1 -Apply    # bascule réelle
param([switch]$Apply)
$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$key = if ($env:DEVOPS_SSH_KEY) { $env:DEVOPS_SSH_KEY } else {
    $line = Select-String -Path (Join-Path $root ".env") -Pattern '^DEVOPS_SSH_KEY=' | Select-Object -First 1
    if (-not $line) { throw "DEVOPS_SSH_KEY manquant" }
    $line.Line.Substring(15).Trim().Trim('"')
}
$hostName = "57.128.186.38"
$user = "ubuntu"
$remote = "/tmp/saas-bascule-prod.sh"
$local = Join-Path $PSScriptRoot "saas-bascule-prod.sh"

Write-Host "Copie script → ${user}@${hostName}:$remote"
scp -i $key -o StrictHostKeyChecking=accept-new $local "${user}@${hostName}:$remote"
$flag = if ($Apply) { "--apply" } else { "" }
Write-Host "Exécution distant (Apply=$Apply)…"
ssh -i $key -o StrictHostKeyChecking=accept-new "${user}@${hostName}" "sudo bash $remote $flag"
