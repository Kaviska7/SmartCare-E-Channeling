param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$base = "http://localhost:$Port/api"
$script:checks = 0
function Assert($Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    $script:checks++; Write-Host "PASS: $Message"
}
function Send($Session, [string]$Method, [string]$Path, $Body = @{}) {
    $token = (Invoke-RestMethod "$base/auth/csrf" -WebSession $Session).token
    Invoke-RestMethod "$base$Path" -Method $Method -WebSession $Session -Headers @{'X-XSRF-TOKEN'=$token} -ContentType 'application/json' -Body ($Body | ConvertTo-Json -Depth 15)
}
function Login([string]$Email, [string]$Password, [string]$Portal) {
    $token = (Invoke-RestMethod "$base/auth/csrf" -SessionVariable session).token
    Invoke-RestMethod "$base/auth/login" -Method Post -WebSession $session -Headers @{'X-XSRF-TOKEN'=$token} -ContentType 'application/x-www-form-urlencoded' -Body @{username=$Email;password=$Password;portal=$Portal} | Out-Null
    return $session
}
function Get($Session, [string]$Path) { $result = Invoke-RestMethod "$base$Path" -WebSession $Session; $result | Write-Output }
function Status([int]$Expected, [scriptblock]$Action, [string]$Message) {
    $actual = 200
    try { & $Action | Out-Null } catch { if ($null -eq $_.Exception.Response) { throw }; $actual = [int]$_.Exception.Response.StatusCode }
    Assert ($actual -eq $Expected) "$Message (HTTP $actual expected $Expected)"
}
$config = Get-Content (Join-Path $PSScriptRoot '../config/application-local.properties')
function Setting([string]$Key) { (($config | Where-Object { $_.StartsWith("$Key=") }) -split '=',2)[1] }
$admin = Login (Setting 'smartcare.bootstrap-admin-email') (Setting 'smartcare.bootstrap-admin-password') 'STAFF'
$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds().ToString()
$password = 'Qa-' + [guid]::NewGuid().ToString('N')
$day = (Get-Date).AddDays(2).ToString('yyyy-MM-dd')
$branch = (Send $admin POST '/branches' @{name="QA $stamp";address='Local automated test'}).id
$specialty = @((Get $admin '/doctors/specialties'))[0].id
$doctorEmail = "smartcare.qa.doctor.$stamp@gmail.com"
$doctorId = (Send $admin POST '/doctors/with-account' @{email=$doctorEmail;password=$password;fullName="QA Doctor $stamp";registrationId="QA-$stamp";specialtyId=$specialty;department='QA';branches=@(@{branchId=$branch;room='QA1';fee=2500})}).id
$doctor = Login $doctorEmail $password 'STAFF'
$receptionEmail = "smartcare.qa.reception.$stamp@gmail.com"
$receptionId = (Send $admin POST '/admin/users' @{email=$receptionEmail;password=$password;fullName="QA Reception $stamp";role='RECEPTION'}).id
$reception = Login $receptionEmail $password 'STAFF'
$managerEmail = "smartcare.qa.manager.$stamp@gmail.com"
$managerId = (Send $admin POST '/admin/users' @{email=$managerEmail;password=$password;fullName="QA Manager $stamp";role='MANAGER'}).id
$manager = Login $managerEmail $password 'STAFF'
$patientEmail = "smartcare.qa.patient.$stamp@gmail.com"
$patientUserId = (Send $admin POST '/admin/patients' @{email=$patientEmail;password=$password;fullName="QA Patient $stamp";nic=$stamp.Substring(1,12);dateOfBirth='1995-01-01';phone='0770000000';address='Local automated test';contactPreference='IN_APP'}).id
$patient = Login $patientEmail $password 'PATIENT'
try {
Assert ((Get $doctor '/auth/session').role -eq 'DOCTOR') 'Doctor login'
Assert ((Get $patient '/auth/session').role -eq 'PATIENT') 'Patient login'
Status 403 { Get $patient '/admin/users' } 'Patient cannot read staff accounts'
Status 403 { Get $manager '/appointments' } 'Manager cannot read patient appointment details'
Status 403 { Get $reception "/doctors/$doctorId/schedules" } 'Reception cannot edit doctor schedules'
Status 403 { Login $patientEmail $password 'STAFF' } 'Wrong sign-in portal rejected'
$scheduleInput = @{scheduleDate=$day;startTime='09:00';endTime='12:00';slotMinutes=15;capacity=12;branchId=$branch}
$scheduleId = (Send $doctor POST "/doctors/$doctorId/schedules" $scheduleInput).id
$slots = @(Get $patient "/doctors/$doctorId/slots?branchId=$branch")
Assert ($slots.Count -eq 12) 'Dated schedule generates 12 slots'
Status 409 { Send $doctor POST "/doctors/$doctorId/schedules" $scheduleInput } 'Overlapping schedules rejected'
$leaveId = (Send $doctor POST "/doctors/$doctorId/leave" @{date=$day;reason='QA test'}).id
Assert (@(Get $patient "/doctors/$doctorId/slots?branchId=$branch").Count -eq 0) 'Leave blocks available slots'
Send $doctor DELETE "/doctors/$doctorId/leave/$leaveId" | Out-Null
$slots = @(Get $patient "/doctors/$doctorId/slots?branchId=$branch")
Assert ($slots.Count -eq 12) 'Removing leave restores slots'
Send $patient POST '/patient/contacts' @{fullName='QA Contact';relationship='Sibling';phone='0770000001'} | Out-Null
$contact = @(Get $patient '/patient/contacts')[0]
Assert ($contact.full_name -eq 'QA Contact') 'Emergency contact saved'
Send $patient DELETE "/patient/contacts/$($contact.id)" | Out-Null
Assert (@(Get $patient '/patient/contacts').Count -eq 0) 'Emergency contact removed'
$visit = (Send $patient POST '/appointments' @{slotId=$slots[0].id;branchId=$branch;paymentMethod='DEMO_CARD'}).id
Assert (@(Get $patient '/appointments')[0].status -eq 'PENDING') 'New booking requires administrator confirmation'
Status 409 { Send $patient POST '/appointments' @{slotId=$slots[0].id;branchId=$branch} } 'Duplicate slot booking rejected'
Status 403 { Send $doctor POST "/appointments/$visit/confirm" } 'Only administrator can confirm booking'
Send $admin POST "/appointments/$visit/confirm" | Out-Null
$receipt = Send $patient POST "/appointments/$visit/payment" @{choice='DEMO_PAID'}
Assert ($receipt.status -eq 'DEMO_PAID' -and $receipt.amount -eq 2500) 'Demo payment uses booked fee'
Status 409 { Send $patient POST "/appointments/$visit/payment" @{choice='DEMO_PAID'} } 'Duplicate payment rejected'
Send $doctor PUT "/appointments/$visit/slot" @{slotId=$slots[1].id} | Out-Null
Assert (@(Get $patient '/appointments')[0].starts_at -eq $slots[1].starts_at) 'Assigned doctor can reschedule'
Send $patient POST "/appointments/$visit/cancel" | Out-Null
Assert ((Get $patient "/appointments/$visit/payment").status -eq 'DEMO_REFUNDED') 'Cancellation refunds demo payment'
$alerts = @(Get $patient '/notifications')
Assert ($alerts.Count -ge 2) 'Appointment alerts saved'
Send $patient POST "/notifications/$($alerts[0].id)/read" | Out-Null
Assert ($null -ne (@(Get $patient '/notifications') | Where-Object id -eq $alerts[0].id).read_at) 'Alert read status saved'
Assert (@(Get $patient '/notifications/upcoming').Count -eq 0) 'Cancellation removes pending reminders'
$patientId = (Get $patient '/patient/profile').id
$queueDay = (Get-Date).ToString('yyyy-MM-dd')
if ((Get-Date).Hour -ge 22) { throw 'Run the queue check before 22:00 for a future consultation today.' }
Send $doctor POST "/doctors/$doctorId/schedules" @{scheduleDate=$queueDay;startTime='22:00';endTime='23:00';slotMinutes=15;capacity=4;branchId=$branch} | Out-Null
$queueSlots = @(Get $patient "/doctors/$doctorId/slots?branchId=$branch" | Where-Object { ([datetime]$_.starts_at).ToString('yyyy-MM-dd') -eq $queueDay })
$queueVisit = (Send $reception POST '/appointments/staff-book' @{patientId=$patientId;slotId=$queueSlots[0].id}).id
Send $admin POST "/appointments/$queueVisit/confirm" | Out-Null
Send $reception POST "/queue/check-in/$queueVisit" | Out-Null
Status 409 { Send $reception POST "/queue/check-in/$queueVisit" } 'Duplicate check-in rejected'
$tokenId = (Send $reception POST "/queue/call-next/${doctorId}?branchId=$branch").tokenId
Assert ($tokenId -gt 0) 'Reception calls next token'
Status 409 { Send $reception POST "/queue/call-next/${doctorId}?branchId=$branch" } 'Finish current token before calling next'
Send $doctor POST "/queue/$tokenId/complete" | Out-Null
Assert ((@(Get $patient '/appointments') | Where-Object id -eq $queueVisit).status -eq 'COMPLETED') 'Doctor completes consultation'
$review = (Send $patient POST '/reviews' @{appointmentId=$queueVisit;rating=5;comment='Automated QA review'}).id
Status 409 { Send $patient POST '/reviews' @{appointmentId=$queueVisit;rating=4} } 'Duplicate review rejected'
Send $patient PUT "/reviews/$review" @{rating=4;comment='Updated automated QA review'} | Out-Null
Assert (@(Get $patient '/reviews')[0].rating -eq 4) 'Review update saved'
Send $patient DELETE "/reviews/$review" | Out-Null
Assert (@(Get $patient '/reviews').Count -eq 0) 'Review removed'
$query = "from=$day&to=$day&doctorId=$doctorId"
foreach ($type in @('summary','doctor_revenue','gross_revenue','refund_cancellation')) {
    $report = Get $manager "/reports?$query&type=$type"
    Assert ($report.report_type -eq $type) "Report type $type"
}
foreach ($format in @('pdf','xlsx','csv')) {
    $file = Invoke-WebRequest "$base/reports/export/$($format)?$query" -WebSession $manager
    Assert ($file.RawContentLength -gt 50) "Report export $format"
}
$templateId = (Send $manager POST '/reports/templates' @{name="QA $stamp";visibility='PRIVATE';reportType='summary';dateMode='FIXED';from=$day;to=$day;doctorId=$doctorId;configuration=@{}}).id
Assert ((Get $manager "/reports/templates/$templateId").name -eq "QA $stamp") 'Report template saved'
Status 404 { Get $admin "/reports/templates/$templateId" } 'Private report template ownership enforced'
$deliveryId = (Send $manager POST '/reports/schedules' @{templateId=$templateId;name="QA $stamp";frequency='DAILY';deliveryChannel='EMAIL';recipients=@($managerEmail);formats=@('pdf');timezone='Asia/Colombo';recurrence=@{};active=$false;templateDefault=$false;nextRunAt=(Get-Date).AddDays(1).ToString('yyyy-MM-ddTHH:mm:ss')}).id
Assert ($deliveryId -gt 0) 'Paused report delivery schedule saved'
Send $manager POST "/reports/schedules/$deliveryId/run" | Out-Null
$storedFiles = @(Get $manager '/reports/files')
Assert ($storedFiles.Count -eq 1) 'Manual report schedule produces retained file'
$download = Invoke-WebRequest "$base/reports/files/$($storedFiles[0].id)" -WebSession $manager
Assert ($download.RawContentLength -gt 100) 'Retained report file downloads'
Send $manager DELETE "/reports/files/$($storedFiles[0].id)" | Out-Null
Assert (@(Get $manager '/reports/files').Count -eq 0) 'Retained report file purged'
Send $manager DELETE "/reports/schedules/$deliveryId" | Out-Null
foreach ($entry in @(Get $manager '/reports/history')) { Send $manager DELETE "/reports/history/$($entry.id)" | Out-Null }
$history = Send $manager POST '/reports/generate' @{name="QA $stamp";from=$day;to=$day;doctorId=$doctorId;type='summary';configuration=@{}}
Assert ($history.id -gt 0) 'Generated report history saved'
Send $manager DELETE "/reports/history/$($history.id)" | Out-Null
Send $manager DELETE "/reports/templates/$templateId" | Out-Null
Send $admin DELETE "/admin/users/$receptionId" | Out-Null
Status 401 { Get $reception '/queue' } 'Deactivated staff session loses access immediately'
Send $admin DELETE "/admin/patients/$patientUserId" | Out-Null
Status 401 { Get $patient '/patient/profile' } 'Deactivated patient session loses access immediately'
Send $admin DELETE "/admin/users/$managerId" | Out-Null
Send $admin PUT "/doctors/$doctorId" @{fullName="QA Doctor $stamp";email=$doctorEmail;specialtyId=$specialty;department='QA';active=$false;branches=@(@{branchId=$branch;room='QA1';fee=2500})} | Out-Null
Assert (@(Get $admin "/doctors/$doctorId/slots?branchId=$branch").Count -eq 0) 'Inactive doctor has no publicly bookable slots'
Write-Host "Completed $script:checks checks. QA records are labelled with $stamp."
} finally {
    foreach ($staffId in @($receptionId,$managerId)) { Send $admin DELETE "/admin/users/$staffId" | Out-Null }
    Send $admin DELETE "/admin/patients/$patientUserId" | Out-Null
    Send $admin PUT "/doctors/$doctorId" @{fullName="QA Doctor $stamp";email=$doctorEmail;specialtyId=$specialty;department='QA';active=$false;branches=@(@{branchId=$branch;room='QA1';fee=2500})} | Out-Null
    Send $admin DELETE "/doctors/$doctorId/schedules/$scheduleId" | Out-Null
    # Retain audit history but hide this run's QA branch and disable its doctor login.
    $builder=[System.Data.SqlClient.SqlConnectionStringBuilder]::new()
    $builder['Data Source']='localhost,1433';$builder['Initial Catalog']='smartcare'
    $builder['User ID']=Setting 'spring.datasource.username';$builder['Password']=Setting 'spring.datasource.password'
    $builder['Encrypt']=$true;$builder['TrustServerCertificate']=$true
    $connection=[System.Data.SqlClient.SqlConnection]::new($builder.ConnectionString)
    try {
        $connection.Open();$command=$connection.CreateCommand()
        $command.CommandText="UPDATE branch SET active=0 WHERE id=@branch AND name=@name; UPDATE app_user SET active=0 WHERE email=@email AND role='DOCTOR';"
        $null=$command.Parameters.AddWithValue('@branch',$branch);$null=$command.Parameters.AddWithValue('@name',"QA $stamp");$null=$command.Parameters.AddWithValue('@email',$doctorEmail)
        $null=$command.ExecuteNonQuery()
    } finally { $connection.Dispose() }
    Write-Host 'QA accounts and doctor deactivated; QA schedule disabled. Audit and visit history retained.'
}


