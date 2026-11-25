param([string]$Server = 'tcp:localhost,1433', [string]$AdminEmail = "$env:USERNAME@localhost")
$ErrorActionPreference = 'Stop'
if (-not (Get-Command sqlcmd -ErrorAction SilentlyContinue)) { throw 'Install SQL Server command-line tools (sqlcmd) first.' }
$configDir = Join-Path $PSScriptRoot '..\config'
[IO.Directory]::CreateDirectory([IO.Path]::GetFullPath($configDir)) | Out-Null
$localFile = Join-Path $configDir 'application-local.properties'
$existing = if (Test-Path $localFile) { Get-Content $localFile } else { @() }
function Get-ExistingProperty([string]$Name) {
    $line = $existing | Where-Object { $_.StartsWith("$Name=") } | Select-Object -First 1
    if ($line) { return $line.Substring($Name.Length + 1) }
    return ''
}
function New-Secret {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes)
}

function Invoke-Sql([string]$Query, [string]$Database = 'master') {
    & sqlcmd -b -C -S $Server -E -d $Database -Q $Query | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "SQL Server setup failed for database $Database. Confirm Windows login has permission and TCP is enabled." }
}

Invoke-Sql "IF DB_ID(N'smartcare') IS NULL CREATE DATABASE smartcare"
$secret = Get-ExistingProperty 'spring.datasource.password'
if (-not $secret) { $secret = New-Secret }
Invoke-Sql "IF SUSER_ID(N'smartcare_app') IS NULL CREATE LOGIN smartcare_app WITH PASSWORD=N'$secret', CHECK_POLICY=ON"
Invoke-Sql "IF USER_ID(N'smartcare_app') IS NULL CREATE USER smartcare_app FOR LOGIN smartcare_app; IF IS_ROLEMEMBER(N'db_owner',N'smartcare_app') <> 1 ALTER ROLE db_owner ADD MEMBER smartcare_app" 'smartcare'

$savedEmail = Get-ExistingProperty 'smartcare.bootstrap-admin-email'
if ($savedEmail) { $AdminEmail = $savedEmail }
$adminPassword = Get-ExistingProperty 'smartcare.bootstrap-admin-password'
if (-not $adminPassword) { $adminPassword = New-Secret }
$managedKeys = @('spring.datasource.url', 'spring.datasource.username', 'spring.datasource.password', 'smartcare.bootstrap-admin-email', 'smartcare.bootstrap-admin-password')
$preservedProperties = @($existing | Where-Object { ($_.Split('=', 2)[0]).Trim() -notin $managedKeys })
$properties = ($preservedProperties + @(
    'spring.datasource.url=jdbc:sqlserver://localhost:1433;databaseName=smartcare;encrypt=true;trustServerCertificate=true',
    'spring.datasource.username=smartcare_app',
    "spring.datasource.password=$secret",
    "smartcare.bootstrap-admin-email=$AdminEmail",
    "smartcare.bootstrap-admin-password=$adminPassword"
)) -join [Environment]::NewLine
[IO.File]::WriteAllText([IO.Path]::GetFullPath($localFile), $properties, [Text.UTF8Encoding]::new($false))
Write-Host "SmartCare database connection configured. Initial admin: $AdminEmail. Password is in ignored config/application-local.properties."
