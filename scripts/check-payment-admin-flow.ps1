param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$base = "http://localhost:$Port/api"
function Assert($Condition,[string]$Message) { if (-not $Condition) { throw $Message } }
function New-Session([string]$Email,[string]$Portal) {
    $token=(Invoke-RestMethod "$base/auth/csrf" -SessionVariable session).token
    Invoke-RestMethod "$base/auth/login" -Method Post -WebSession $session -Headers @{'X-XSRF-TOKEN'=$token} -ContentType 'application/x-www-form-urlencoded' -Body @{username=$Email;password='password';portal=$Portal} | Out-Null
    return $session
}
function Send-Api($Session,[string]$Method,[string]$Path,$Body) {
    $token=(Invoke-RestMethod "$base/auth/csrf" -WebSession $Session).token
    return Invoke-RestMethod "$base$Path" -Method $Method -WebSession $Session -Headers @{'X-XSRF-TOKEN'=$token} -ContentType 'application/json' -Body ($Body | ConvertTo-Json -Depth 5)
}
function Expect-Status([int]$Wanted,[scriptblock]$Action) {
    try { & $Action | Out-Null; throw "Expected HTTP $Wanted but request succeeded" }
    catch { $status=[int]$_.Exception.Response.StatusCode; if($status -ne $Wanted){throw "Expected HTTP $Wanted, got $status : $($_.Exception.Message)"} }
}
$config=Join-Path $PSScriptRoot '..\config\application-local.properties'
$adminEmail=((Get-Content -LiteralPath $config | Where-Object { $_.StartsWith('smartcare.bootstrap-admin-email=') }) -split '=',2)[1]
$admin=New-Session $adminEmail 'STAFF'
$patient=New-Session 'patient2@test.local' 'PATIENT'
$other=New-Session 'patient@test.local' 'PATIENT'
$doctor=New-Session 'doctor2@test.local' 'STAFF'
$profile=Invoke-RestMethod "$base/doctors/me" -WebSession $doctor
$slots=Invoke-RestMethod "$base/doctors/$($profile.id)/slots"
Assert (@($slots).Count -ge 1) 'No open slot for the demo payment check.'
$booking=Send-Api $patient 'POST' '/appointments' @{slotId=$slots[0].id;branchId=$profile.branch_id;paymentMethod='DEMO_CARD'}
$adminAppointments=Invoke-RestMethod "$base/appointments" -WebSession $admin
$before=$adminAppointments | Where-Object { $_.id -eq $booking.id } | Select-Object -First 1
Assert ($before -and $before.patient_name -and $before.doctor_name -and $before.branch_name -and $before.payment_method -eq 'DEMO_CARD' -and $before.payment_status -eq 'PENDING_DEMO') 'Admin cannot see the new booking and pending demo method.'
$own=Invoke-RestMethod "$base/appointments" -WebSession $patient
Assert ($own | Where-Object { $_.id -eq $booking.id -and $_.payment_status -eq 'PENDING_DEMO' }) 'Patient cannot see pending demo payment.'
Expect-Status 403 { Invoke-RestMethod "$base/appointments/$($booking.id)/payment" -WebSession $other }
$receipt=Send-Api $patient 'POST' "/appointments/$($booking.id)/payment" @{choice='DEMO_PAID'}
Assert ($receipt.status -eq 'DEMO_PAID' -and $receipt.choice -eq 'DEMO_CARD') 'Demo receipt is wrong.'
Expect-Status 409 { Send-Api $patient 'POST' "/appointments/$($booking.id)/payment" @{choice='DEMO_PAID'} }
$updatedAppointments=Invoke-RestMethod "$base/appointments" -WebSession $admin
$after=$updatedAppointments | Where-Object { $_.id -eq $booking.id } | Select-Object -First 1
Assert ($after.payment_status -eq 'DEMO_PAID' -and [decimal]$after.fee -eq [decimal]$receipt.amount) 'Admin payment status or amount did not update.'
$doctorVisits=Invoke-RestMethod "$base/appointments" -WebSession $doctor
Assert ($doctorVisits | Where-Object { $_.id -eq $booking.id }) 'Doctor combined appointment panel cannot load the new visit.'
Write-Host "Payment/admin flow OK: appointment #$($booking.id) is visible to admin, moved from pending to demo paid, and remains in the doctor appointment list."
