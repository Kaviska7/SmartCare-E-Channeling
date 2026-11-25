param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$base = "http://localhost:$Port/api"
$config = Join-Path $PSScriptRoot '..\config\application-local.properties'
$adminEmail = ((Get-Content -LiteralPath $config | Where-Object { $_.StartsWith('smartcare.bootstrap-admin-email=') }) -split '=', 2)[1]
function Assert($Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function New-Session([string]$Email, [string]$Password = 'password') {
    $csrf = (Invoke-RestMethod "$base/auth/csrf" -SessionVariable session).token
    $portal = if ($Email -match 'patient') { 'PATIENT' } else { 'STAFF' }
    Invoke-RestMethod "$base/auth/login" -Method Post -WebSession $session -Headers @{'X-XSRF-TOKEN'=$csrf} -ContentType 'application/x-www-form-urlencoded' -Body @{username=$Email;password=$Password;portal=$portal} | Out-Null
    return $session
}
function Send-Api($Session,[string]$Method,[string]$Path,$Body) {
    $csrf = (Invoke-RestMethod "$base/auth/csrf" -WebSession $Session).token
    return Invoke-RestMethod "$base$Path" -Method $Method -WebSession $Session -Headers @{'X-XSRF-TOKEN'=$csrf} -ContentType 'application/json' -Body ($Body | ConvertTo-Json -Depth 5)
}
function Expect-Status([int]$Wanted,[scriptblock]$Action) {
    try { & $Action | Out-Null; throw "Expected HTTP $Wanted but request succeeded" }
    catch {
        $status = [int]$_.Exception.Response.StatusCode
        if ($status -ne $Wanted) { throw "Expected HTTP $Wanted, got $status : $($_.Exception.Message)" }
    }
}

$publicDoctors = Invoke-RestMethod "$base/doctors"
$specialties = Invoke-RestMethod "$base/doctors/specialties"
Assert (@($publicDoctors).Count -ge 1) 'Public doctor list is empty; run add-local-test-users.ps1 first.'
Assert (@($specialties).Count -ge 1) 'Public specialty list is empty.'
$today = (Get-Date).ToString('yyyy-MM-dd')
foreach ($publicDoctor in @($publicDoctors)) {
    $open = Invoke-RestMethod "$base/doctors/$($publicDoctor.id)/slots"
    $countToday = @($open | Where-Object { ([string]$_.starts_at).StartsWith($today) }).Count
    Assert ($countToday -eq [int]$publicDoctor.today_open_slots) "Today's public availability count is wrong for doctor $($publicDoctor.id)."
}
$firstDoctor = @($publicDoctors)[0]
$publicSlots = Invoke-RestMethod "$base/doctors/$($firstDoctor.id)/slots"
Assert (@($publicSlots).Count -ge 1) 'Public availability is empty.'
$null = Invoke-RestMethod "$base/auth/csrf" -SessionVariable guest
Expect-Status 401 { Send-Api $guest 'POST' '/appointments' @{slotId=$publicSlots[0].id} }

$admin = New-Session $adminEmail
$staff = Invoke-RestMethod "$base/admin/users" -WebSession $admin
$extraReception = $staff | Where-Object { $_.email -eq 'reception2@test.local' } | Select-Object -First 1
if (-not $extraReception) {
    Send-Api $admin 'POST' '/admin/users' @{email='reception2@test.local';fullName='Second Test Receptionist';password='password';role='RECEPTION'} | Out-Null
    $staff = Invoke-RestMethod "$base/admin/users" -WebSession $admin
}
$reception2 = New-Session 'reception2@test.local'
Assert ((Invoke-RestMethod "$base/auth/session" -WebSession $reception2).role -eq 'RECEPTION') 'Admin-created staff login failed.'
$primary = $staff | Where-Object { $_.email -eq 'doctor@test.local' } | Select-Object -First 1
Assert ($primary -and $primary.doctor_id) 'First doctor account is not linked to a profile.'
$second = $staff | Where-Object { $_.email -eq 'doctor2@test.local' } | Select-Object -First 1
if (-not $second) {
    $specialty = @($specialties)[0]
    Send-Api $admin 'POST' '/doctors/with-account' @{email='doctor2@test.local';password='password';fullName='Dr. Second Test';specialtyId=$specialty.id;department='Outpatient';room='B2';fee=1800} | Out-Null
    $staff = Invoke-RestMethod "$base/admin/users" -WebSession $admin
    $second = $staff | Where-Object { $_.email -eq 'doctor2@test.local' } | Select-Object -First 1
}
Assert ($second.doctor_id -and $second.doctor_id -ne $primary.doctor_id) 'Doctors do not have separate linked accounts.'
$doctor1 = New-Session 'doctor@test.local'
$doctor2 = New-Session 'doctor2@test.local'
$mine1 = Invoke-RestMethod "$base/doctors/me" -WebSession $doctor1
$mine2 = Invoke-RestMethod "$base/doctors/me" -WebSession $doctor2
Assert ($mine1.id -eq $primary.doctor_id -and $mine2.id -eq $second.doctor_id) 'Doctor login linked to wrong profile.'
$schedules = Invoke-RestMethod "$base/doctors/$($mine1.id)/schedules" -WebSession $doctor1
Assert (@($schedules).Count -ge 1) 'Primary doctor has no schedule.'
$schedule = @($schedules)[0]
Expect-Status 403 { Invoke-RestMethod "$base/doctors/$($mine1.id)/schedules" -WebSession $doctor2 }
Expect-Status 403 { Send-Api $doctor2 'PUT' "/doctors/$($mine1.id)/schedules/$($schedule.id)" @{dayOfWeek=$schedule.day_of_week;startTime='09:00';endTime='12:00';slotMinutes=30;capacity=6} }
Send-Api $doctor1 'PUT' "/doctors/$($mine1.id)/schedules/$($schedule.id)" @{dayOfWeek=$schedule.day_of_week;startTime=([string]$schedule.start_time).Substring(0,5);endTime=([string]$schedule.end_time).Substring(0,5);slotMinutes=$schedule.slot_minutes;capacity=$schedule.capacity} | Out-Null

$patient = New-Session 'patient@test.local'
$ownBookings = Invoke-RestMethod "$base/appointments" -WebSession $patient
$existing = @($ownBookings | Where-Object { $_.doctor_id -eq $mine1.id -and $_.status -eq 'CONFIRMED' })
if ($existing.Count -eq 0) {
    $openSlots = Invoke-RestMethod "$base/doctors/$($mine1.id)/slots" -WebSession $patient
    Assert (@($openSlots).Count -ge 1) 'Schedule edit left no open slots.'
    $booking = Send-Api $patient 'POST' '/appointments' @{slotId=@($openSlots)[0].id}
    $appointmentId = $booking.id
} else { $appointmentId = $existing[0].id }
$doctorAppointments = Invoke-RestMethod "$base/appointments" -WebSession $doctor1
Assert (@($doctorAppointments | Where-Object { $_.id -eq $appointmentId -and $_.patient_name -and $_.phone }).Count -eq 1) 'Assigned doctor cannot see basic patient details.'
$otherAppointments = Invoke-RestMethod "$base/appointments" -WebSession $doctor2
Assert (@($otherAppointments | Where-Object { $_.id -eq $appointmentId }).Count -eq 0) 'Other doctor can see assigned patient.'

$reception = New-Session 'reception@test.local'
$manager = New-Session 'manager@test.local'
$patient2 = New-Session 'patient2@test.local'
$secondNic = (Invoke-RestMethod "$base/patient/profile" -WebSession $patient2).nic
$matchedPatients = Invoke-RestMethod "$base/appointments/patients?search=$([uri]::EscapeDataString($secondNic))" -WebSession $reception
$secondPatient = @($matchedPatients | Where-Object { $_.nic -eq $secondNic }) | Select-Object -First 1
if ($secondPatient) {
    $existingStaffBooking = @(Invoke-RestMethod "$base/appointments" -WebSession $patient2 | Where-Object { $_.doctor_id -eq $mine1.id -and $_.status -eq 'CONFIRMED' }) | Select-Object -First 1
    if ($existingStaffBooking) { $staffBookingId = $existingStaffBooking.id }
    else {
        $staffSlots = Invoke-RestMethod "$base/doctors/$($mine1.id)/slots"
        Assert (@($staffSlots).Count -ge 1) 'No open slot for staff booking test.'
        $staffSlotId = @($staffSlots)[0].id
        $staffBookingId = (Send-Api $reception 'POST' '/appointments/staff-book' @{patientId=$secondPatient.id;slotId=$staffSlotId}).id
        Expect-Status 409 { Send-Api $reception 'POST' '/appointments/staff-book' @{patientId=$secondPatient.id;slotId=$staffSlotId} }
    }
    Assert (@(Invoke-RestMethod "$base/appointments" -WebSession $patient2 | Where-Object { $_.id -eq $staffBookingId }).Count -eq 1) 'Reception booking did not appear for the patient.'
    Assert (@(Invoke-RestMethod "$base/appointments" -WebSession $doctor1 | Where-Object { $_.id -eq $staffBookingId }).Count -eq 1) 'Reception booking did not appear for the assigned doctor.'
    Assert (@(Invoke-RestMethod "$base/appointments" -WebSession $doctor2 | Where-Object { $_.id -eq $staffBookingId }).Count -eq 0) 'Other doctor saw the reception booking.'
}
Expect-Status 403 { Invoke-RestMethod "$base/appointments/patients?search=$([uri]::EscapeDataString($secondNic))" -WebSession $doctor2 }
Expect-Status 403 { Invoke-RestMethod "$base/appointments/patients?search=$([uri]::EscapeDataString($secondNic))" -WebSession $manager }
$null = Invoke-RestMethod "$base/appointments" -WebSession $reception
$null = Invoke-RestMethod "$base/queue" -WebSession $reception
$null = Invoke-RestMethod "$base/queue" -WebSession $manager
$report = Invoke-RestMethod "$base/reports?from=$today&to=$today&doctorId=$($mine1.id)" -WebSession $manager
Assert ($null -ne $report.bookings) 'Manager report failed.'
$pdf = Invoke-WebRequest "$base/reports/export/pdf?from=$today&to=$today&doctorId=$($mine1.id)" -WebSession $manager -UseBasicParsing
$xlsx = Invoke-WebRequest "$base/reports/export/xlsx?from=$today&to=$today&doctorId=$($mine1.id)" -WebSession $manager -UseBasicParsing
Assert ($pdf.RawContentLength -gt 100 -and $xlsx.RawContentLength -gt 100) 'Manager report exports failed.'
Expect-Status 403 { Invoke-RestMethod "$base/reports?from=$today&to=$today" -WebSession $reception }
Expect-Status 403 { Invoke-RestMethod "$base/admin/users" -WebSession $manager }
Expect-Status 403 { Invoke-RestMethod "$base/admin/users" -WebSession $patient }

$account = Invoke-RestMethod "$base/account" -WebSession $doctor2
Assert ($account.email -eq 'doctor2@test.local') 'Doctor account details failed.'
Send-Api $doctor2 'PUT' '/account' @{email=$account.email;fullName=($account.full_name + ' Check')} | Out-Null
Assert ((Invoke-RestMethod "$base/account" -WebSession $doctor2).full_name -eq ($account.full_name + ' Check')) 'Account detail edit failed.'
Send-Api $doctor2 'PUT' '/account' @{email=$account.email;fullName=$account.full_name} | Out-Null
Send-Api $doctor2 'PUT' '/account/password' @{currentPassword='password';newPassword='password123'} | Out-Null
$changed = New-Session 'doctor2@test.local' 'password123'
Send-Api $changed 'PUT' '/account/password' @{currentPassword='password123';newPassword='password'} | Out-Null
$restored = New-Session 'doctor2@test.local'
Assert ((Invoke-RestMethod "$base/auth/session" -WebSession $restored).role -eq 'DOCTOR') 'Doctor password reset verification failed.'
Write-Host 'Role flow OK: public browse, booking login, separate doctor accounts, own schedule editing, assigned patient details, reception, manager, admin access, and password change.'
