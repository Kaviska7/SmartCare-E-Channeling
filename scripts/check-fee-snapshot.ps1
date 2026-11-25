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
$doctor = New-Session 'doctor2@test.local'
$patient = New-Session 'patient@test.local'
$profile = Invoke-RestMethod "$base/doctors/me" -WebSession $doctor
$visits = Invoke-RestMethod "$base/appointments" -WebSession $patient
$visit = $visits | Where-Object { $_.doctor_id -eq $profile.id -and $_.status -eq 'CONFIRMED' -and -not $_.payment_status } | Select-Object -First 1
if (-not $visit) {
    $slots=Invoke-RestMethod "$base/doctors/$($profile.id)/slots"
    if(-not @($slots).Count) { throw 'No available slot for fee snapshot check.' }
    $created=Send-Api $patient 'POST' '/appointments' @{slotId=@($slots)[0].id;branchId=$profile.branch_id;paymentMethod='PAY_AT_HOSPITAL'}
    $visits=Invoke-RestMethod "$base/appointments" -WebSession $patient
    $visit=$visits | Where-Object { $_.id -eq $created.id } | Select-Object -First 1
}
$originalFee = [decimal]$profile.fee
$bookedFee = [decimal]$visit.fee
$profileBody = @{fullName=$profile.full_name;specialtyId=$profile.specialty_id;department=$profile.department;room=$profile.room;fee=($originalFee + 250);active=$profile.active;branchId=$profile.branch_id}
try {
    Send-Api $doctor 'PUT' "/doctors/$($profile.id)" $profileBody | Out-Null
    $updatedVisits = Invoke-RestMethod "$base/appointments" -WebSession $patient
    $changed = $updatedVisits | Where-Object { $_.id -eq $visit.id } | Select-Object -First 1
    if ([decimal]$changed.fee -ne $bookedFee) { throw 'Changing the doctor fee changed a booked appointment.' }
    $receipt = Send-Api $patient 'POST' "/appointments/$($visit.id)/payment" @{choice='PAY_AT_HOSPITAL'}
    if ([decimal]$receipt.amount -ne $bookedFee) { throw 'The receipt did not use the fee saved at booking.' }
    Write-Host "Fee snapshot OK: appointment #$($visit.id) and receipt stayed at LKR $bookedFee while doctor fee changed."
} finally {
    $profileBody.fee = $originalFee
    Send-Api $doctor 'PUT' "/doctors/$($profile.id)" $profileBody | Out-Null
}
