$ErrorActionPreference = 'Stop'
$config = Join-Path $PSScriptRoot '..\config\application-local.properties'
if (-not (Test-Path $config)) { throw 'Run scripts/setup-local.ps1 first.' }
$lines = Get-Content $config
function Value([string]$Name) {
    $line = $lines | Where-Object { $_.StartsWith("$Name=") } | Select-Object -First 1
    if (-not $line) { throw "Missing local database setting: $Name" }
    return $line.Substring($Name.Length + 1)
}
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
SELECT COUNT(*) FROM sys.tables WHERE name IN
('app_user','patient','emergency_contact','specialty','doctor','schedule_template',
 'slot','appointment','queue_token','demo_payment','notification','audit_event','appointment_review','doctor_leave','branch',
 'doctor_branch','report_template','report_schedule','generated_report_file','generated_report')
'@
    $count = [int]$command.ExecuteScalar()
    if ($count -ne 20) { throw "SQL connection works, but only $count of 20 application tables exist. Start SmartCare once to apply migrations." }
    Write-Host 'SQL Server connection OK. Database smartcare has all 20 application tables.'
} finally {
    $connection.Dispose()
}
