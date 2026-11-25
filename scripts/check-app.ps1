param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$config = Join-Path $PSScriptRoot '..\config\application-local.properties'
if (-not (Test-Path $config)) { throw 'Run scripts/setup-local.ps1 first.' }
$lines = Get-Content $config
function Value([string]$Name) {
    $line = $lines | Where-Object { $_.StartsWith("$Name=") } | Select-Object -First 1
    if (-not $line) { throw "Missing setting: $Name" }
    return $line.Substring($Name.Length + 1)
}
$base = "http://localhost:$Port/api"
$token = (Invoke-RestMethod "$base/auth/csrf" -SessionVariable session).token
Invoke-RestMethod "$base/auth/login" -Method Post -WebSession $session -Headers @{'X-XSRF-TOKEN'=$token} -ContentType 'application/x-www-form-urlencoded' -Body @{
    username = Value 'smartcare.bootstrap-admin-email'
    password = Value 'smartcare.bootstrap-admin-password'
    portal = 'STAFF'
} | Out-Null
$me = Invoke-RestMethod "$base/auth/session" -WebSession $session
if ($me.role -ne 'ADMIN') { throw 'Initial administrator login failed.' }
Invoke-RestMethod "$base/admin/users" -WebSession $session | Out-Null
Invoke-RestMethod "$base/doctors" -WebSession $session | Out-Null
Invoke-RestMethod "$base/doctors/specialties" -WebSession $session | Out-Null
Write-Host 'SmartCare API OK. Administrator login and database-backed screens work.'
