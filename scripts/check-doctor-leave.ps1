param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$base = "http://localhost:$Port/api"
function Assert($Condition,[string]$Message) { if (-not $Condition) { throw $Message } }
function New-Session([string]$Email) {
    $token = (Invoke-RestMethod "$base/auth/csrf" -SessionVariable session).token
    Invoke-RestMethod "$base/auth/login" -Method Post -WebSession $session -Headers @{'X-XSRF-TOKEN'=$token} -ContentType 'application/x-www-form-urlencoded' -Body @{username=$Email;password='password';portal='STAFF'} | Out-Null
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
$doctor = New-Session 'doctor2@test.local'
$other = New-Session 'doctor@test.local'
$mine = Invoke-RestMethod "$base/doctors/me" -WebSession $doctor
$appointments = Invoke-RestMethod "$base/appointments" -WebSession $doctor
$open = Invoke-RestMethod "$base/doctors/$($mine.id)/slots"
$tomorrow = (Get-Date).Date.AddDays(1)
$bookedDays = @($appointments | Where-Object { $_.status -eq 'CONFIRMED' } | ForEach-Object { ([datetime]$_.starts_at).ToString('yyyy-MM-dd') })
$candidate = $open | Where-Object { [datetime]$_.starts_at -ge $tomorrow -and ([string]$_.starts_at).Substring(0,10) -notin $bookedDays } | Select-Object -First 1
Assert $candidate 'No unbooked future schedule day is available for the leave check.'
$date = ([datetime]$candidate.starts_at).ToString('yyyy-MM-dd')
Expect-Status 403 { Invoke-RestMethod "$base/doctors/$($mine.id)/leave" -WebSession $other }
Expect-Status 403 { Send-Api $other 'POST' "/doctors/$($mine.id)/leave" @{date=$date;reason='not my calendar'} }
$created = Send-Api $doctor 'POST' "/doctors/$($mine.id)/leave" @{date=$date;reason='Local test'}
try {
    $after = Invoke-RestMethod "$base/doctors/$($mine.id)/slots"
    Assert (-not @($after | Where-Object { ([string]$_.starts_at).StartsWith($date) }).Count) 'Open slots remained on the leave date.'
    Expect-Status 409 { Send-Api $doctor 'POST' "/doctors/$($mine.id)/leave" @{date=$date;reason='duplicate'} }
    $leaves = Invoke-RestMethod "$base/doctors/$($mine.id)/leave" -WebSession $doctor
    Assert ($leaves | Where-Object { $_.id -eq $created.id }) 'Saved leave is not visible to its doctor.'
} finally {
    Send-Api $doctor 'DELETE' "/doctors/$($mine.id)/leave/$($created.id)" @{} | Out-Null
}
$restored = Invoke-RestMethod "$base/doctors/$($mine.id)/slots"
Assert (@($restored | Where-Object { ([string]$_.starts_at).StartsWith($date) }).Count -ge 1) 'Open slots were not restored after removing future leave.'
$booked = $appointments | Where-Object { $_.status -eq 'CONFIRMED' -and [datetime]$_.starts_at -ge $tomorrow } | Select-Object -First 1
if ($booked) {
    $bookedDate = ([datetime]$booked.starts_at).ToString('yyyy-MM-dd')
    Expect-Status 409 { Send-Api $doctor 'POST' "/doctors/$($mine.id)/leave" @{date=$bookedDate;reason='would hide booking'} }
}
Write-Host "Doctor leave OK: owner-only, open times blocked on $date, duplicate rejected, removal restores times."
