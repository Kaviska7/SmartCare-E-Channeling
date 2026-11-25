# Local classroom accounts use the typed password "password".
# The database still stores a BCrypt hash, never the plain password.
$ErrorActionPreference = 'Stop'
$config = Join-Path $PSScriptRoot '..\config\application-local.properties'
$lines = Get-Content -LiteralPath $config
function Value([string]$Name) {
    $line = $lines | Where-Object { $_.StartsWith("$Name=") } | Select-Object -First 1
    if (-not $line) { throw "Missing local setting: $Name" }
    return $line.Substring($Name.Length + 1)
}
$adminEmail = Value 'smartcare.bootstrap-admin-email'
$builder = [System.Data.SqlClient.SqlConnectionStringBuilder]::new()
$builder['Data Source'] = 'localhost,1433'
$builder['Initial Catalog'] = 'smartcare'
$builder['User ID'] = Value 'spring.datasource.username'
$builder['Password'] = Value 'spring.datasource.password'
$builder['Encrypt'] = $true
$builder['TrustServerCertificate'] = $true
$connection = [System.Data.SqlClient.SqlConnection]::new($builder.ConnectionString)
try {
    $connection.Open()
    $command = $connection.CreateCommand()
    $command.CommandText = @'
UPDATE app_user SET password_hash=@hash
WHERE email=@admin OR email IN
('doctor@test.local','doctor2@test.local','reception@test.local','reception2@test.local','manager@test.local','patient@test.local','patient2@test.local')
'@
    $command.Parameters.Add('@hash', [System.Data.SqlDbType]::NVarChar, 255).Value = '$2a$10$pBvRQCZUKzgrpHHj4T4Ic.uMo5JVp21/ae3QG6lb9LPCEk5pt1.c6'
    $command.Parameters.Add('@admin', [System.Data.SqlDbType]::NVarChar, 255).Value = $adminEmail
    if ($command.ExecuteNonQuery() -lt 1) { throw 'Initial administrator account was not found.' }
} finally {
    $connection.Dispose()
}
$updated = $lines | ForEach-Object {
    if ($_.StartsWith('smartcare.bootstrap-admin-password=')) { 'smartcare.bootstrap-admin-password=password' } else { $_ }
}
[IO.File]::WriteAllLines([IO.Path]::GetFullPath($config), [string[]]$updated, [Text.UTF8Encoding]::new($false))
Write-Host 'Local test account password set to: password'
