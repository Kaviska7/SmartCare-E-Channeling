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
function Expect-PortalDenied([string]$Email,[string]$Portal) {
    $token=(Invoke-RestMethod "$base/auth/csrf" -SessionVariable wrong).token
    Expect-Status 403 { Invoke-RestMethod "$base/auth/login" -Method Post -WebSession $wrong -Headers @{'X-XSRF-TOKEN'=$token} -ContentType 'application/x-www-form-urlencoded' -Body @{username=$Email;password='password';portal=$Portal} }
    Assert (-not (Invoke-RestMethod "$base/auth/session" -WebSession $wrong).id) 'Wrong portal left an authenticated session.'
}
$config=Join-Path $PSScriptRoot '..\config\application-local.properties'
$adminEmail=((Get-Content -LiteralPath $config | Where-Object { $_.StartsWith('smartcare.bootstrap-admin-email=') }) -split '=',2)[1]
$admin=New-Session $adminEmail 'STAFF'
$doctor=New-Session 'doctor2@test.local' 'STAFF'
$otherDoctor=New-Session 'doctor@test.local' 'STAFF'
Expect-PortalDenied 'patient@test.local' 'STAFF'
Expect-PortalDenied 'doctor2@test.local' 'PATIENT'
$staff=Invoke-RestMethod "$base/admin/users" -WebSession $admin
Assert (@($staff | Where-Object { $_.role -eq 'DOCTOR' }).Count -ge 2) 'Doctor accounts are not listed separately.'
Assert (@($staff | Where-Object { $_.role -eq 'RECEPTION' }).Count -ge 1) 'Reception staff are not listed.'
Expect-Status 403 { Invoke-RestMethod "$base/admin/users" -WebSession $doctor }
$stamp=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$branch=Send-Api $admin 'POST' '/branches' @{name="Test branch $stamp";address='Local test'}
try {
    $allBranches=Invoke-RestMethod "$base/branches"
    Assert (@($allBranches | Where-Object { $_.id -eq $branch.id }).Count -eq 1) 'New branch missing.'
} finally { Send-Api $admin 'DELETE' "/branches/$($branch.id)" @{} | Out-Null }
$specialty=Send-Api $admin 'POST' '/doctors/specialties' @{name="Test specialty $stamp"}
try { $allSpecialties=Invoke-RestMethod "$base/doctors/specialties"; Assert (@($allSpecialties | Where-Object { $_.id -eq $specialty.id }).Count -eq 1) 'New specialty missing.' }
finally { Send-Api $admin 'DELETE' "/doctors/specialties/$($specialty.id)" @{} | Out-Null }
$patientEmail="adminpatient-$stamp@test.local"
$newPatient=Send-Api $admin 'POST' '/admin/patients' @{fullName='Admin Created Test Patient';email=$patientEmail;password='password';nic=$stamp.ToString().Substring(1,12);dateOfBirth='1995-05-15';phone='0771234567';address='Local test'}
Assert ($newPatient.id -gt 0) 'Admin patient registration failed.'
$patient=New-Session $patientEmail 'PATIENT'
Expect-PortalDenied $patientEmail 'STAFF'
Expect-Status 403 { Invoke-RestMethod "$base/admin/users" -WebSession $patient }
Expect-Status 403 { Invoke-RestMethod "$base/doctors/me" -WebSession $patient }
$doctors=Invoke-RestMethod "$base/doctors"
$ownProfile=Invoke-RestMethod "$base/doctors/me" -WebSession $doctor
$myDoctor=$doctors | Where-Object { $_.id -eq $ownProfile.id } | Select-Object -First 1
Assert $myDoctor 'Doctor profile missing from public list.'
Expect-Status 409 { Send-Api $admin 'DELETE' "/doctors/specialties/$($ownProfile.specialty_id)" @{} }
$profileBody=@{fullName=$ownProfile.full_name;specialtyId=$ownProfile.specialty_id;department=$ownProfile.department;room=$ownProfile.room;fee=$ownProfile.fee;active=$ownProfile.active;branchId=$ownProfile.branch_id}
Send-Api $doctor 'PUT' "/doctors/$($ownProfile.id)" $profileBody | Out-Null
Assert ((Invoke-RestMethod "$base/doctors/me" -WebSession $doctor).branch_id -eq $ownProfile.branch_id) 'Doctor profile branch edit failed.'
Expect-Status 403 { Send-Api $otherDoctor 'PUT' "/doctors/$($ownProfile.id)" $profileBody }
$filtered=Invoke-RestMethod "$base/doctors?branchId=$($myDoctor.branch_id)"
Assert ($filtered | Where-Object { $_.id -eq $myDoctor.id }) 'Doctor branch filter failed.'
$slots=Invoke-RestMethod "$base/doctors/$($myDoctor.id)/slots"
Assert (@($slots).Count -ge 2) 'Need two available future slots for the doctor check.'
Expect-Status 400 { Send-Api $patient 'POST' '/appointments' @{slotId=$slots[0].id;branchId=999999} }
$visit=Send-Api $patient 'POST' '/appointments' @{slotId=$slots[0].id;branchId=$myDoctor.branch_id}
$doctorVisits=Invoke-RestMethod "$base/appointments" -WebSession $doctor
Assert ($doctorVisits | Where-Object { $_.id -eq $visit.id }) 'Doctor cannot see own new appointment.'
Assert ($doctorVisits | Where-Object { $_.status -eq 'COMPLETED' }) 'Doctor appointment history omitted past visits.'
Expect-Status 403 { Send-Api $otherDoctor 'POST' "/appointments/$($visit.id)/cancel" @{} }
Send-Api $doctor 'PUT' "/appointments/$($visit.id)/slot" @{slotId=$slots[1].id} | Out-Null
Send-Api $doctor 'POST' "/appointments/$($visit.id)/cancel" @{} | Out-Null
$patientVisits=Invoke-RestMethod "$base/appointments" -WebSession $patient
Assert ($patientVisits | Where-Object { $_.id -eq $visit.id -and $_.status -eq 'CANCELLED' -and $_.branch_name -eq $myDoctor.branch_name }) 'Cancellation or branch history is wrong.'
Write-Host "Role flow OK: separate sign-ins, admin patient, specialty/branch management, doctor-owned appointment #$($visit.id), branch history. Test patient: $patientEmail"
