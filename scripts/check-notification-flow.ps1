param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$base = "http://localhost:$Port/api"
function Assert($Condition,[string]$Message) { if (-not $Condition) { throw $Message } }
function Send-Api($Session,[string]$Method,[string]$Path,$Body) {
    $token=(Invoke-RestMethod "$base/auth/csrf" -WebSession $Session).token
    return Invoke-RestMethod "$base$Path" -Method $Method -WebSession $Session -Headers @{'X-XSRF-TOKEN'=$token} -ContentType 'application/json' -Body ($Body | ConvertTo-Json -Depth 5)
}
function New-Session([string]$Email,[string]$Portal) {
    $token=(Invoke-RestMethod "$base/auth/csrf" -SessionVariable session).token
    Invoke-RestMethod "$base/auth/login" -Method Post -WebSession $session -Headers @{'X-XSRF-TOKEN'=$token} -ContentType 'application/x-www-form-urlencoded' -Body @{username=$Email;password='password';portal=$Portal} | Out-Null
    return $session
}
$doctor=New-Session 'doctor2@test.local' 'STAFF'
$doctorProfile=Invoke-RestMethod "$base/doctors/me" -WebSession $doctor
$slots=Invoke-RestMethod "$base/doctors/$($doctorProfile.id)/slots"
$future=@($slots | Where-Object { [datetime]$_.starts_at -gt (Get-Date).AddHours(25) } | Select-Object -First 2)
Assert ($future.Count -eq 2) 'Need two open slots more than 25 hours away for reminder checks.'
$stamp=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$email="alertpatient-$stamp@test.local"
$nic=$stamp.ToString().Substring(1,12)
$phone='0771234567'
$guestToken=(Invoke-RestMethod "$base/auth/csrf" -SessionVariable guest).token
Invoke-RestMethod "$base/auth/register" -Method Post -WebSession $guest -Headers @{'X-XSRF-TOKEN'=$guestToken} -ContentType 'application/json' -Body (@{fullName='Alert Test Patient';email=$email;password='password';nic=$nic;dateOfBirth='1994-04-04';phone=$phone;address='Local test';contactPreference='IN_APP_AND_TEXT'} | ConvertTo-Json) | Out-Null
$patient=New-Session $email 'PATIENT'
$profile=Invoke-RestMethod "$base/patient/profile" -WebSession $patient
Assert ($profile.contact_preference -eq 'IN_APP_AND_TEXT') 'Registration did not save text alert opt-in.'
$booking=Send-Api $patient 'POST' '/appointments' @{slotId=$future[0].id;branchId=$doctorProfile.branch_id;paymentMethod='PAY_AT_HOSPITAL'}
$id=$booking.id
Assert ($id -gt 0) 'Booking failed.'
$inbox=Invoke-RestMethod "$base/notifications" -WebSession $patient
$booked=@($inbox | Where-Object { $_.appointment_id -eq $id -and $_.type -eq 'BOOKED' }) | Select-Object -First 1
Assert $booked 'Patient booking confirmation is missing.'
$doctorInbox=Invoke-RestMethod "$base/notifications" -WebSession $doctor
Assert (@($doctorInbox | Where-Object { $_.appointment_id -eq $id -and $_.type -eq 'BOOKED' }).Count) 'Doctor booking alert is missing.'
$upcoming=Invoke-RestMethod "$base/notifications/upcoming" -WebSession $patient
Assert (@($upcoming | Where-Object { $_.appointment_id -eq $id }).Count) 'Future reminder is missing.'
Send-Api $patient 'POST' "/notifications/$($booked.id)/read" @{} | Out-Null
$inbox=Invoke-RestMethod "$base/notifications" -WebSession $patient
Assert (@($inbox | Where-Object { $_.id -eq $booked.id -and $_.read_at }).Count) 'Mark read did not persist.'
Send-Api $patient 'PUT' "/appointments/$id/slot" @{slotId=$future[1].id} | Out-Null
$inbox=Invoke-RestMethod "$base/notifications" -WebSession $patient
$moved=@($inbox | Where-Object { $_.appointment_id -eq $id -and $_.type -eq 'RESCHEDULED' }) | Select-Object -First 1
Assert $moved 'Patient reschedule alert is missing.'
$doctorInbox=Invoke-RestMethod "$base/notifications" -WebSession $doctor
Assert (@($doctorInbox | Where-Object { $_.appointment_id -eq $id -and $_.type -eq 'RESCHEDULED' }).Count) 'Doctor reschedule alert is missing.'
$upcoming=Invoke-RestMethod "$base/notifications/upcoming" -WebSession $patient
Assert (@($upcoming | Where-Object { $_.appointment_id -eq $id }).Count) 'Rescheduled reminder is missing.'
Send-Api $patient 'POST' "/appointments/$id/cancel" @{} | Out-Null
$inbox=Invoke-RestMethod "$base/notifications" -WebSession $patient
$cancelled=@($inbox | Where-Object { $_.appointment_id -eq $id -and $_.type -eq 'CANCELLED' }) | Select-Object -First 1
Assert $cancelled 'Patient cancellation alert is missing.'
$doctorInbox=Invoke-RestMethod "$base/notifications" -WebSession $doctor
Assert (@($doctorInbox | Where-Object { $_.appointment_id -eq $id -and $_.type -eq 'CANCELLED' }).Count) 'Doctor cancellation alert is missing.'
$upcoming=Invoke-RestMethod "$base/notifications/upcoming" -WebSession $patient
Assert (-not @($upcoming | Where-Object { $_.appointment_id -eq $id }).Count) 'Cancelled visit still has a pending reminder.'
$folder=Join-Path $PSScriptRoot '..\config\message-preview'
$events=@(@{id=$booked.id;type='BOOKED'},@{id=$moved.id;type='RESCHEDULED'},@{id=$cancelled.id;type='CANCELLED'})
for($attempt=0;$attempt -lt 15;$attempt++) {
    if (@($events | Where-Object { -not (Test-Path -LiteralPath (Join-Path $folder "notification-$($_.id).txt")) }).Count -eq 0) { break }
    Start-Sleep -Seconds 2
}
foreach($event in $events) {
    $file=Join-Path $folder "notification-$($event.id).txt"
    Assert (Test-Path -LiteralPath $file) "Missing $($event.type) text preview."
    $contents=Get-Content -LiteralPath $file -Raw
    Assert ($contents.Contains("To: $phone") -and $contents.Contains("Appointment: #$id") -and $contents.Contains("Event: $($event.type)") -and $contents.Contains('No SMS was sent.')) "Wrong $($event.type) text preview."
}
$profileBody=@{fullName=$profile.full_name;email=$profile.email;nic=$profile.nic;phone=$profile.phone;address=$profile.address;contactPreference='IN_APP'}
Send-Api $patient 'PUT' '/patient/profile' $profileBody | Out-Null
Assert ((Invoke-RestMethod "$base/patient/profile" -WebSession $patient).contact_preference -eq 'IN_APP') 'Opt-out did not persist.'
Write-Host "Notification flow OK: patient/doctor booking, reschedule and cancellation alerts; reminder removal; read state; three local text files; opt-in and opt-out. Appointment #$id."
