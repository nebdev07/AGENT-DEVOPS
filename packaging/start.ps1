# VPS Autopilot : démarre la console sur http://127.0.0.1:8088 (Java 21 requis).
# Les réglages et l'état restent dans ce dossier : .env et state\ (à sauvegarder, jamais à partager).
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java" }
$ErrorActionPreference = "Continue"
$versionLine = "$(& $java -version 2>&1 | Select-Object -First 1)"
$ErrorActionPreference = "Stop"
if ($versionLine -notmatch 'version "(\d+)') {
    Write-Error "Java introuvable. Installe Java 21 (Temurin) ou renseigne JAVA_HOME."
}
if ([int]$Matches[1] -lt 21) {
    Write-Error "Java $($Matches[1]) trouvé : Java 21 ou plus est requis."
}
if (-not (Test-Path ".env")) {
    Copy-Item ".env.example" ".env"
    Write-Host "Fichier .env créé : renseigne DEVOPS_SSH_KEY (chemin de la clé privée de l'agent), puis relance."
    exit 1
}
New-Item -ItemType Directory -Force -Path "state" | Out-Null
Write-Host "Console : http://127.0.0.1:8088  (Ctrl+C pour arrêter)"
& $java -jar "vps-autopilot.jar"
