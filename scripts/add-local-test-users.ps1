param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$base = "http://localhost:$Port/api"
$config = Join-Path $PSScriptRoot '..\config\application-local.properties'
$lines = Get-Content -LiteralPath $config
function Value([string]$Name) {
    $line = $lines | Where-Object { $_.StartsWith("$Name=") } | Select-Object -First 1
    if (-not $line) { throw "Missing local setting: $Name" }
    return $line.Substring($Name.Length + 1)
}
function New-Session([string]$Email) {
    $token = (Invoke-RestMethod "$base/auth/csrf" -SessionVariable session).token
    $portal = if ($Email -match 'patient') { 'PATIENT' } else { 'STAFF' }
    Invoke-RestMethod "$base/auth/login" -Method Post -WebSession $session -Headers @{'X-XSRF-TOKEN'=$token} -ContentType 'application/x-www-form-urlencoded' -Body @{ username=$Email; password='password'; portal=$portal } | Out-Null
    return $session
}
function Send-Api($Session, [string]$Method, [string]$Path, $Body) {
    $token = (Invoke-RestMethod "$base/auth/csrf" -WebSession $Session).token
    return Invoke-RestMethod "$base$Path" -Method $Method -WebSession $Session -Headers @{'X-XSRF-TOKEN'=$token} -ContentType 'application/json' -Body ($Body | ConvertTo-Json -Depth 5)
}

$admin = New-Session (Value 'smartcare.bootstrap-admin-email')
foreach ($member in @(
    @{ email='reception@test.local'; fullName='Local Receptionist'; role='RECEPTION' },
    @{ email='manager@test.local'; fullName='Local Manager'; role='MANAGER' }
)) {
    $staff = Invoke-RestMethod "$base/admin/users" -WebSession $admin
    if (-not ($staff | Where-Object { $_.email -eq $member.email })) {
        Send-Api $admin 'POST' '/admin/users' @{ email=$member.email; fullName=$member.fullName; role=$member.role; password='password' } | Out-Null
    }
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
    $command.CommandText = "SELECT COUNT(*) FROM app_user WHERE email='patient@test.local'"
    $patientExists = [int]$command.ExecuteScalar()
} finally { $connection.Dispose() }
if (-not $patientExists) {
    Send-Api $admin 'POST' '/auth/register' @{
        email='patient@test.local'; fullName='Local Test Patient'; password='password'
        nic='199500000001'; dateOfBirth='1995-05-05'; phone='0771234567'; address='Local test'
    } | Out-Null
}

$specialties = Invoke-RestMethod "$base/doctors/specialties" -WebSession $admin
$specialty = $specialties | Where-Object { $_.name -eq 'General Medicine' } | Select-Object -First 1
if (-not $specialty) {
    $created = Send-Api $admin 'POST' '/doctors/specialties' @{ name='General Medicine' }
    $specialty = @{ id=$created.id }
}
$staff = Invoke-RestMethod "$base/admin/users" -WebSession $admin
$doctorAccount = $staff | Where-Object { $_.email -eq 'doctor@test.local' } | Select-Object -First 1
$doctor = if ($doctorAccount -and $doctorAccount.doctor_id) { @{ id=$doctorAccount.doctor_id } } else { $null }
if (-not $doctor) {
    if ($doctorAccount) {
        $created = Send-Api $admin 'POST' '/doctors' @{ userId=$doctorAccount.id; fullName='Dr. Local Test'; specialtyId=$specialty.id; department='Outpatient'; room='A1'; fee=1500; active=$true }
    } else {
        $created = Send-Api $admin 'POST' '/doctors/with-account' @{ email='doctor@test.local'; password='password'; fullName='Dr. Local Test'; specialtyId=$specialty.id; department='Outpatient'; room='A1'; fee=1500 }
    }
    $doctor = @{ id=$created.id }
}
$schedules = Invoke-RestMethod "$base/doctors/$($doctor.id)/schedules" -WebSession $admin
if (-not $schedules) {
    $day = [int](Get-Date).AddDays(1).DayOfWeek
    if ($day -eq 0) { $day = 7 }
    Send-Api $admin 'POST' "/doctors/$($doctor.id)/schedules" @{ dayOfWeek=$day; startTime='09:00'; endTime='12:00'; slotMinutes=30; capacity=6 } | Out-Null
}

foreach ($account in @(
    @{ email='doctor@test.local'; role='DOCTOR' },
    @{ email='reception@test.local'; role='RECEPTION' },
    @{ email='manager@test.local'; role='MANAGER' },
    @{ email='patient@test.local'; role='PATIENT' }
)) {
    $login = New-Session $account.email
    $session = Invoke-RestMethod "$base/auth/session" -WebSession $login
    if ($session.role -ne $account.role) { throw "Login verification failed for $($account.email)" }
}
$slots = Invoke-RestMethod "$base/doctors/$($doctor.id)/slots" -WebSession $admin
if (-not $slots) { throw 'Test doctor has no available slots.' }
Write-Host 'Local users are ready: admin, doctor, reception, manager, patient. Each logs in with password.'
Write-Host "Doctor has $(@($slots).Count) available slots for booking."
