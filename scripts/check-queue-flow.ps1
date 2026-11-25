param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$base = "http://localhost:$Port/api"
function New-Session([string]$Email) {
    $token = (Invoke-RestMethod "$base/auth/csrf" -SessionVariable session).token
    $portal = if ($Email -match 'patient') { 'PATIENT' } else { 'STAFF' }
    Invoke-RestMethod "$base/auth/login" -Method Post -WebSession $session -Headers @{'X-XSRF-TOKEN'=$token} -ContentType 'application/x-www-form-urlencoded' -Body @{username=$Email;password='password';portal=$portal} | Out-Null
    return $session
}
function Send-Api($Session,[string]$Method,[string]$Path,$Body) {
    $token = (Invoke-RestMethod "$base/auth/csrf" -WebSession $Session).token
    return Invoke-RestMethod "$base$Path" -Method $Method -WebSession $Session -Headers @{'X-XSRF-TOKEN'=$token} -ContentType 'application/json' -Body ($Body | ConvertTo-Json -Depth 5)
}
function Expect-Conflict([scriptblock]$Action) {
    try { & $Action | Out-Null; throw 'Expected a conflict, but the action succeeded.' }
    catch { if ([int]$_.Exception.Response.StatusCode -ne 409) { throw } }
}
$doctor = New-Session 'doctor2@test.local'
$reception = New-Session 'reception@test.local'
$manager = New-Session 'manager@test.local'
$doctorId = (Invoke-RestMethod "$base/doctors/me" -WebSession $doctor).id
$now = Get-Date
$startHour = $now.AddHours(1).Hour
if ($startHour -ge 23) { throw 'Run this check before 22:00 so a future appointment can be checked in today.' }
$weekday = [int]$now.DayOfWeek
if ($weekday -eq 0) { $weekday = 7 }
$today = $now.ToString('yyyy-MM-dd')
$openToday = Invoke-RestMethod "$base/doctors/$doctorId/slots" -WebSession $doctor
$availableToday = @($openToday | Where-Object { ([datetime]$_.starts_at).ToString('yyyy-MM-dd') -eq $today })
if (-not $availableToday.Count) {
    $schedules = Invoke-RestMethod "$base/doctors/$doctorId/schedules" -WebSession $doctor
    $newHour = $null
    for($hour=$startHour;$hour -le 21;$hour++) {
        $candidateStart = '{0:00}:00' -f $hour
        $candidateEnd = '{0:00}:00' -f ($hour + 1)
        $overlap = @($schedules | Where-Object { $_.active -and $_.day_of_week -eq $weekday -and ([string]$_.start_time).Substring(0,5) -lt $candidateEnd -and ([string]$_.end_time).Substring(0,5) -gt $candidateStart })
        if(-not $overlap.Count) { $newHour=$hour; break }
    }
    if($null -eq $newHour) { throw 'No free schedule hour remains today for the queue check.' }
    $start = '{0:00}:00' -f $newHour
    $end = '{0:00}:00' -f ($newHour + 1)
    Send-Api $doctor 'POST' "/doctors/$doctorId/schedules" @{dayOfWeek=$weekday;startTime=$start;endTime=$end;slotMinutes=30;capacity=2} | Out-Null
}
$guestToken = (Invoke-RestMethod "$base/auth/csrf" -SessionVariable guest).token
try {
    Invoke-RestMethod "$base/auth/register" -Method Post -WebSession $guest -Headers @{'X-XSRF-TOKEN'=$guestToken} -ContentType 'application/json' -Body (@{email='patient2@test.local';fullName='Second Test Patient';password='password';nic='199400000002';dateOfBirth='1994-04-04';phone='0771234568';address='Local test'} | ConvertTo-Json) | Out-Null
} catch { if ([int]$_.Exception.Response.StatusCode -ne 409) { throw } }
$patient = New-Session 'patient2@test.local'
$patientProfile = Invoke-RestMethod "$base/patient/profile" -WebSession $patient
$bookings = Invoke-RestMethod "$base/appointments" -WebSession $patient
$booking = $null
foreach ($candidate in @($bookings | Where-Object { $_.doctor_id -eq $doctorId -and $_.status -eq 'CONFIRMED' -and ([datetime]$_.starts_at).ToString('yyyy-MM-dd') -eq $today })) {
    $candidateMatches = Invoke-RestMethod "$base/queue/find?search=$($candidate.id)" -WebSession $reception
    if (@($candidateMatches | Where-Object { $_.appointment_id -eq $candidate.id -and $_.can_check_in }).Count) { $booking = $candidate; break }
}
if (-not $booking) {
    $slots = Invoke-RestMethod "$base/doctors/$doctorId/slots" -WebSession $patient
    $slot = @($slots | Where-Object { ([datetime]$_.starts_at).ToString('yyyy-MM-dd') -eq $today }) | Select-Object -First 1
    if (-not $slot) { throw 'No future slot available today for queue check.' }
    $bookingId = (Send-Api $patient 'POST' '/appointments' @{slotId=$slot.id}).id
} else { $bookingId = $booking.id }
$matches = Invoke-RestMethod "$base/queue/find?search=$bookingId" -WebSession $reception
$found = @($matches | Where-Object { $_.appointment_id -eq $bookingId }) | Select-Object -First 1
if (-not $found -or $found.status -ne 'CONFIRMED' -or -not $found.branch_name -or -not $found.can_check_in) { throw 'Reception lookup did not show the confirmed appointment and branch.' }
$nicMatches = Invoke-RestMethod "$base/queue/find?search=$([uri]::EscapeDataString($patientProfile.nic))" -WebSession $reception
$phoneMatches = Invoke-RestMethod "$base/queue/find?search=$([uri]::EscapeDataString($patientProfile.phone))" -WebSession $reception
$idMatches = Invoke-RestMethod "$base/queue/find?search=$($found.patient_id)" -WebSession $reception
$byNic = @($nicMatches | Where-Object { $_.appointment_id -eq $bookingId })
$byPhone = @($phoneMatches | Where-Object { $_.appointment_id -eq $bookingId })
$byPatientId = @($idMatches | Where-Object { $_.appointment_id -eq $bookingId })
if (-not $byNic.Count -or -not $byPhone.Count -or -not $byPatientId.Count) { throw 'Reception lookup failed for NIC, phone, or patient ID.' }
try { Invoke-RestMethod "$base/queue/find?search=$bookingId" -WebSession $patient | Out-Null; throw 'Patient unexpectedly accessed reception lookup.' }
catch { if ([int]$_.Exception.Response.StatusCode -ne 403) { throw } }
$tokenNumber = (Send-Api $reception 'POST' "/queue/check-in/$bookingId" @{}).tokenNumber
$afterMatches = Invoke-RestMethod "$base/queue/find?search=$bookingId" -WebSession $reception
$afterLookup = @($afterMatches | Where-Object { $_.appointment_id -eq $bookingId }) | Select-Object -First 1
if ($afterLookup) { throw 'Reception lookup returned an appointment with an active token.' }
Expect-Conflict { Send-Api $reception 'POST' "/queue/check-in/$bookingId" @{} }
$queue = Invoke-RestMethod "$base/queue" -WebSession $manager
$waiting = @($queue | Where-Object { $_.appointment_id -eq $bookingId }) | Select-Object -First 1
if (-not $waiting -or $waiting.token_number -ne $tokenNumber -or $waiting.status -ne 'WAITING') { throw 'Reception token did not appear in manager queue.' }
$pending = @($queue | Where-Object { $_.doctor_id -eq $doctorId -and $_.branch_id -eq $found.branch_id -and $_.status -eq 'WAITING' } | Sort-Object token_number)
foreach($expected in $pending) {
    $calledId = (Send-Api $reception 'POST' "/queue/call-next/${doctorId}?branchId=$($found.branch_id)" @{}).tokenId
    if ($calledId -ne $expected.id) { throw 'Call-next did not follow token order.' }
    Send-Api $reception 'POST' "/queue/$calledId/complete" @{} | Out-Null
    if($calledId -eq $waiting.id) { break }
}
$final = Invoke-RestMethod "$base/queue" -WebSession $doctor
if (-not @($final | Where-Object { $_.id -eq $calledId -and $_.status -eq 'COMPLETED' }).Count) { throw 'Doctor queue did not show a completed token.' }
Write-Host "Queue flow OK: appointment #$bookingId, token $tokenNumber, duplicate blocked, called and completed."
