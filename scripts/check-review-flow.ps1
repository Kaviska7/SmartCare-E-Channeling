param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$base = "http://localhost:$Port/api"
function Assert($Condition,[string]$Message) { if (-not $Condition) { throw $Message } }
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
function Expect-Status([int]$Wanted,[scriptblock]$Action) {
    try { & $Action | Out-Null; throw "Expected HTTP $Wanted but request succeeded" }
    catch {
        $status = [int]$_.Exception.Response.StatusCode
        if ($status -ne $Wanted) { throw "Expected HTTP $Wanted, got $status : $($_.Exception.Message)" }
    }
}
$patient = New-Session 'patient2@test.local'
$other = New-Session 'patient@test.local'
$doctor = New-Session 'doctor2@test.local'
$visits = Invoke-RestMethod "$base/appointments" -WebSession $patient
$completed = $visits | Where-Object { $_.status -eq 'COMPLETED' } | Select-Object -First 1
Assert $completed 'No completed visit for patient2; finish a visit before checking reviews.'
$reviews = Invoke-RestMethod "$base/reviews" -WebSession $patient
$existing = $reviews | Where-Object { $_.appointment_id -eq $completed.id } | Select-Object -First 1
if (-not $existing) {
    Send-Api $patient 'POST' '/reviews' @{appointmentId=$completed.id;rating=5;comment='Helpful consultation'} | Out-Null
}
$reviews = Invoke-RestMethod "$base/reviews" -WebSession $patient
Assert ($reviews | Where-Object { $_.appointment_id -eq $completed.id -and $_.rating -eq 5 }) 'Saved review is missing from patient inbox.'
Expect-Status 409 { Send-Api $patient 'POST' '/reviews' @{appointmentId=$completed.id;rating=4;comment='duplicate'} }
Expect-Status 403 { Send-Api $other 'POST' '/reviews' @{appointmentId=$completed.id;rating=4;comment='other patient'} }
Expect-Status 403 { Send-Api $doctor 'POST' '/reviews' @{appointmentId=$completed.id;rating=4;comment='staff'} }
$notCompleted = $visits | Where-Object { $_.status -ne 'COMPLETED' } | Select-Object -First 1
if ($notCompleted) { Expect-Status 409 { Send-Api $patient 'POST' '/reviews' @{appointmentId=$notCompleted.id;rating=5;comment='too early'} } }
$public = Invoke-RestMethod "$base/doctors"
$doctorCard = $public | Where-Object { $_.id -eq $completed.doctor_id } | Select-Object -First 1
Assert ($doctorCard -and [int]$doctorCard.review_count -ge 1 -and [decimal]$doctorCard.average_rating -ge 1) 'Public doctor rating did not update.'
Write-Host "Review flow OK: completed appointment #$($completed.id), owner-only, one review, public doctor rating."
