# VPS Autopilot : construit le paquet de livraison dist\vps-autopilot-<version>.zip (+ .sha256).
# Usage : .\scripts\release.ps1 [-SkipTests]   (Java 21 et Maven dans le PATH, ou JAVA_HOME)
param([switch]$SkipTests)
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    $version = ([xml](Get-Content pom.xml)).project.version
    if ($SkipTests) { mvn -q -B -DskipTests package } else { mvn -q -B package }
    if ($LASTEXITCODE -ne 0) { throw "La construction Maven a échoué." }

    $name = "vps-autopilot-$version"
    $out = Join-Path $root "dist\$name"
    if (Test-Path $out) { Remove-Item $out -Recurse -Force }
    New-Item -ItemType Directory -Force -Path (Join-Path $out "state") | Out-Null

    Copy-Item "target\devops-agent-$version.jar" (Join-Path $out "vps-autopilot.jar")
    Copy-Item "packaging\start.ps1", "packaging\start.cmd", "packaging\LISEZMOI.txt" $out
    $sh = [IO.File]::ReadAllText((Join-Path $root "packaging\start.sh")) -replace "`r", ""
    [IO.File]::WriteAllText((Join-Path $out "start.sh"), $sh, (New-Object Text.UTF8Encoding($false)))
    Copy-Item ".env.example" $out
    Copy-Item "state\agent-local.example.yml" (Join-Path $out "state")

    $zip = Join-Path $root "dist\$name.zip"
    if (Test-Path $zip) { Remove-Item $zip -Force }
    Compress-Archive -Path $out -DestinationPath $zip
    $hash = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()
    [IO.File]::WriteAllText("$zip.sha256", "$hash  $name.zip`n", (New-Object Text.UTF8Encoding($false)))
    Write-Host "Paquet : $zip"
    Write-Host "SHA-256 : $hash"
} finally {
    Pop-Location
}
