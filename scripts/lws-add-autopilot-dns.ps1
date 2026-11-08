# Requires LWS Client API credentials (panel → Api LWS).
# Usage:
#   $env:LWS_API_LOGIN = 'LWS-XXXXXX'
#   $env:LWS_API_KEY   = '…'
#   .\scripts\lws-add-autopilot-dns.ps1
param(
    [string]$Domain = "inovne.com",
    [string]$Name = "autopilot",
    [string]$Ip = "57.128.186.38",
    [int]$Ttl = 900
)

$login = $env:LWS_API_LOGIN
$key = $env:LWS_API_KEY
if ([string]::IsNullOrWhiteSpace($login) -or [string]::IsNullOrWhiteSpace($key)) {
    throw "Set LWS_API_LOGIN and LWS_API_KEY (LWS panel → Api LWS), then re-run."
}

$headers = @{
    "X-Auth-Login" = $login
    "X-Auth-Pass"  = $key
    "Content-Type" = "application/json"
    "Accept"       = "application/json"
}
$body = @{ type = "A"; name = $Name; value = $Ip; ttl = $Ttl } | ConvertTo-Json
$uri = "https://api.lws.net/v1/domain/$Domain/zdns"
Write-Host "POST $uri  A $Name.$Domain → $Ip (ttl=$Ttl)"
$r = Invoke-RestMethod -Method Post -Uri $uri -Headers $headers -Body $body
$r | ConvertTo-Json -Depth 6
Write-Host "Verify: Resolve-DnsName $Name.$Domain"
