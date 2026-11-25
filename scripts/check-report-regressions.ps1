param([int]$Port=8080)
$ErrorActionPreference='Stop'
$base="http://localhost:$Port/api"
$config=Get-Content (Join-Path $PSScriptRoot '../config/application-local.properties')
function Setting($Name){(($config | Where-Object { $_.StartsWith("$Name=") }) -split '=',2)[1]}
$csrf=(Invoke-RestMethod "$base/auth/csrf" -SessionVariable session).token
Invoke-RestMethod "$base/auth/login" -Method Post -WebSession $session -Headers @{'X-XSRF-TOKEN'=$csrf} -ContentType 'application/x-www-form-urlencoded' -Body @{username=(Setting 'smartcare.bootstrap-admin-email');password=(Setting 'smartcare.bootstrap-admin-password');portal='STAFF'} | Out-Null
$script:checks=0
function Assert($Condition,$Message){if(-not $Condition){throw $Message};$script:checks++;Write-Host "PASS: $Message"}
function Send($Method,$Path,$Body=@{}){$token=(Invoke-RestMethod "$base/auth/csrf" -WebSession $session).token;Invoke-RestMethod "$base$Path" -Method $Method -WebSession $session -Headers @{'X-XSRF-TOKEN'=$token} -ContentType 'application/json' -Body ($Body | ConvertTo-Json -Depth 10)}
$criteria=@{name='QA report regression';from=(Get-Date).AddDays(-30).ToString('yyyy-MM-dd');to=(Get-Date).AddDays(7).ToString('yyyy-MM-dd');type='summary';configuration=@{groupBy='branch';columns=@('branch','appointments','net_revenue');metrics=@('appointments','net_revenue');sortDirection='DESC'}}
foreach($type in @('summary','doctor_revenue','gross_revenue','refund_cancellation')) {
    $criteria.type=$type
    $preview=Send POST '/reports/preview' $criteria
    Assert ($preview.group_by -eq 'branch') "$type respects branch grouping"
    Assert ((@($preview.rows | Measure-Object appointments -Sum)[0].Sum) -eq $preview.bookings) "$type grouped bookings equal summary total"
    Assert ([Math]::Abs((@($preview.rows | Measure-Object net_revenue -Sum)[0].Sum)-$preview.simulated_revenue) -lt 0.01) "$type grouped revenue equals summary total"
    Assert (($preview.summary_metrics.PSObject.Properties.Name -join ',') -eq 'appointments,net_revenue') "$type shows only selected summary metrics"
}
$criteria.type='summary';$criteria.configuration.groupBy='doctor'
$preview=Send POST '/reports/preview' $criteria
Assert (@($preview.rows.key | Select-Object -Unique).Count -eq @($preview.rows).Count) 'Doctor rows have unique grouped keys across branches'
$generated=$null;$template=$null;$schedule=$null
try {
    $generated=Send POST '/reports/generate' $criteria
    foreach($format in @('csv','pdf','xlsx')) {
        $token=(Invoke-RestMethod "$base/auth/csrf" -WebSession $session).token
        $file=Invoke-WebRequest "$base/reports/history/$($generated.id)/export/$format" -Method Post -WebSession $session -Headers @{'X-XSRF-TOKEN'=$token}
        Assert ($file.RawContentLength -gt 50) "Configured $format export succeeds"
    }
    $template=Send POST '/reports/templates' @{name=('QA timezone '+[guid]::NewGuid().ToString('N'));reportType='summary';dateMode='FIXED';from=$criteria.from;to=$criteria.to;configuration=@{}}
    $next=[DateTime]::UtcNow.AddHours(1).ToString('yyyy-MM-ddTHH:mm:ss')
    $schedule=Send POST '/reports/schedules' @{templateId=$template.id;name='QA UTC validation';frequency='DAILY';deliveryChannel='EMAIL';recipients=@('smartcare.qa@gmail.com');formats=@('pdf');timezone='UTC';recurrence=@{};active=$false;templateDefault=$false;nextRunAt=$next}
    Assert ($schedule.id -gt 0) 'UTC time one hour in the future is accepted'
    $savedList=Invoke-RestMethod "$base/reports/schedules" -WebSession $session
    $saved=$savedList | Where-Object id -eq $schedule.id
    $savedTime=if($saved.next_run_at -is [datetime]){$saved.next_run_at.ToString('yyyy-MM-ddTHH:mm')}else{([string]$saved.next_run_at).Substring(0,16)}
    Assert ($saved.timezone -eq 'UTC' -and $savedTime -eq $next.Substring(0,16)) 'Schedule round trip preserves selected wall time and timezone'
    $templatePreview=Send POST '/reports/templates/preview' @{name='QA preview';reportType='summary';dateMode='PRESET';configuration=@{dateRange=@{preset='TODAY'};timezone='UTC';groupBy='department';metrics=@('appointments');columns=@('department','appointments')}}
    Assert ($templatePreview.group_by -eq 'department' -and $templatePreview.from -eq [DateTime]::UtcNow.ToString('yyyy-MM-dd')) 'Template preview applies grouping and dynamic date range'
} finally {
    if($schedule){Send DELETE "/reports/schedules/$($schedule.id)" | Out-Null}
    if($template){Send DELETE "/reports/templates/$($template.id)" | Out-Null}
    if($generated){Send DELETE "/reports/history/$($generated.id)" | Out-Null}
}
Write-Host "Completed $script:checks report regression checks. Temporary reports, files, template and schedule removed."
