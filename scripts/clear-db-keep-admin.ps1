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
    $command.CommandText = @"
SET XACT_ABORT ON;
BEGIN TRANSACTION;

IF OBJECT_ID('password_reset_token', 'U') IS NOT NULL DELETE FROM password_reset_token;
IF OBJECT_ID('generated_report_file', 'U') IS NOT NULL DELETE FROM generated_report_file;
IF OBJECT_ID('generated_report', 'U') IS NOT NULL DELETE FROM generated_report;
IF OBJECT_ID('report_schedule', 'U') IS NOT NULL DELETE FROM report_schedule;
IF OBJECT_ID('report_template', 'U') IS NOT NULL DELETE FROM report_template;
IF OBJECT_ID('notification', 'U') IS NOT NULL DELETE FROM notification;
IF OBJECT_ID('audit_event', 'U') IS NOT NULL DELETE FROM audit_event;
IF OBJECT_ID('demo_payment', 'U') IS NOT NULL DELETE FROM demo_payment;
IF OBJECT_ID('queue_token', 'U') IS NOT NULL DELETE FROM queue_token;
IF OBJECT_ID('appointment_review', 'U') IS NOT NULL DELETE FROM appointment_review;
IF OBJECT_ID('appointment', 'U') IS NOT NULL DELETE FROM appointment;
IF OBJECT_ID('slot', 'U') IS NOT NULL DELETE FROM slot;
IF OBJECT_ID('schedule_template', 'U') IS NOT NULL DELETE FROM schedule_template;
IF OBJECT_ID('emergency_contact', 'U') IS NOT NULL DELETE FROM emergency_contact;
IF OBJECT_ID('patient', 'U') IS NOT NULL DELETE FROM patient;
IF OBJECT_ID('doctor_leave', 'U') IS NOT NULL DELETE FROM doctor_leave;
IF OBJECT_ID('doctor_branch', 'U') IS NOT NULL DELETE FROM doctor_branch;
IF OBJECT_ID('doctor', 'U') IS NOT NULL DELETE FROM doctor;
IF OBJECT_ID('specialty', 'U') IS NOT NULL DELETE FROM specialty;
IF OBJECT_ID('branch', 'U') IS NOT NULL DELETE FROM branch;

DELETE FROM app_user WHERE role <> 'ADMIN';

COMMIT TRANSACTION;
"@
    $command.ExecuteNonQuery()
    Write-Host 'Database cleared successfully. Only Super Admin (ADMIN) user account(s) retained.'
} finally {
    $connection.Dispose()
}
