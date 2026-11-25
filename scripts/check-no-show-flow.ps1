param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$base = "http://localhost:$Port/api"
function New-Session([string]$Email) {
    $csrf = (Invoke-RestMethod "$base/auth/csrf" -SessionVariable session).token
    $portal = if ($Email -match 'patient') { 'PATIENT' } else { 'STAFF' }
    Invoke-RestMethod "$base/auth/login" -Method Post -WebSession $session -Headers @{'X-XSRF-TOKEN'=$csrf} -ContentType 'application/x-www-form-urlencoded' -Body @{username=$Email;password='password';portal=$portal} | Out-Null
    return $session
}
function Send-Api($Session,[string]$Path,$Body) {
    $csrf = (Invoke-RestMethod "$base/auth/csrf" -WebSession $Session).token
    return Invoke-RestMethod "$base$Path" -Method Post -WebSession $Session -Headers @{'X-XSRF-TOKEN'=$csrf} -ContentType 'application/json' -Body ($Body | ConvertTo-Json -Depth 5)
}
function Expect-Status([int]$Expected,[scriptblock]$Action) {
    try { & $Action | Out-Null; throw "Expected HTTP $Expected" }
    catch { if ([int]$_.Exception.Response.StatusCode -ne $Expected) { throw } }
}
$reception = New-Session 'reception@test.local'
$doctor = New-Session 'doctor2@test.local'
$otherDoctor = New-Session 'doctor@test.local'
$patient = New-Session 'patient2@test.local'
$manager = New-Session 'manager@test.local'
$doctorId = (Invoke-RestMethod "$base/doctors/me" -WebSession $doctor).id
$today = (Get-Date).ToString('yyyy-MM-dd')
$bookings = Invoke-RestMethod "$base/appointments" -WebSession $patient
$booking = @($bookings | Where-Object { $_.doctor_id -eq $doctorId -and $_.status -eq 'CONFIRMED' -and ([datetime]$_.starts_at).ToString('yyyy-MM-dd') -eq $today }) | Select-Object -First 1
if (-not $booking) {
    $secondNic = (Invoke-RestMethod "$base/patient/profile" -WebSession $patient).nic
    $matches = Invoke-RestMethod "$base/appointments/patients?search=$([uri]::EscapeDataString($secondNic))" -WebSession $reception
    $patientId = @($matches)[0].id
    $openSlots = Invoke-RestMethod "$base/doctors/$doctorId/slots"
    $slot = $openSlots | Where-Object { ([datetime]$_.starts_at).ToString('yyyy-MM-dd') -eq $today } | Select-Object -First 1
    if (-not $slot) { throw 'No future slot today for the no-show check.' }
    $appointmentId = (Send-Api $reception '/appointments/staff-book' @{patientId=$patientId;slotId=$slot.id}).id
} else { $appointmentId = $booking.id }
$tokenNumber = (Send-Api $reception "/queue/check-in/$appointmentId" @{}).tokenNumber
$queueBranchId = (Invoke-RestMethod "$base/queue" -WebSession $reception | Where-Object appointment_id -eq $appointmentId | Select-Object -First 1).branch_id
$called = (Send-Api $reception "/queue/call-next/${doctorId}?branchId=$queueBranchId" @{}).tokenId
$doctorQueue = Invoke-RestMethod "$base/queue" -WebSession $doctor
$token = $doctorQueue | Where-Object { $_.id -eq $called } | Select-Object -First 1
if (-not $token -or $token.appointment_id -ne $appointmentId -or $token.token_number -ne $tokenNumber) { throw 'Unexpected token called.' }
Expect-Status 403 { Send-Api $otherDoctor "/queue/$called/skip" @{} }
Send-Api $doctor "/queue/$called/skip" @{} | Out-Null
Expect-Status 409 { Send-Api $doctor "/queue/$called/skip" @{} }
$patientVisits = Invoke-RestMethod "$base/appointments" -WebSession $patient
$result = $patientVisits | Where-Object { $_.id -eq $appointmentId } | Select-Object -First 1
if ($result.status -ne 'NO_SHOW') { throw 'Appointment was not marked NO_SHOW.' }
$messages = Invoke-RestMethod "$base/notifications" -WebSession $patient
if (-not @($messages | Where-Object { $_.appointment_id -eq $appointmentId -and $_.type -eq 'NO_SHOW' }).Count) { throw 'Patient did not receive the no-show message.' }
$report = Invoke-RestMethod "$base/reports?from=$today&to=$today&doctorId=$doctorId" -WebSession $manager
if ([int]$report.no_shows -lt 1) { throw 'Manager report did not count the no-show.' }
Write-Host "No-show flow OK: appointment #$appointmentId, token $tokenNumber, assigned-doctor action, patient message, and manager report."
