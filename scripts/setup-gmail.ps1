param([string]$SenderEmail)
$ErrorActionPreference = 'Stop'
if (-not $SenderEmail) { $SenderEmail = Read-Host 'Gmail address that will send password reset emails' }
$SenderEmail = $SenderEmail.Trim().ToLowerInvariant()
if ($SenderEmail -notmatch '^[a-z0-9._%+-]+@gmail\.com$') { throw 'Enter a valid @gmail.com sender address.' }

Write-Host 'Enable Google 2-Step Verification, then create an App Password for SmartCare.'
Write-Host 'Enter that App Password here, not your normal Gmail password. Input is hidden.'
$securePassword = Read-Host 'Gmail App Password' -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try {
    $appPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) -replace '\s', ''
    if ($appPassword -notmatch '^[a-zA-Z0-9]{16}$') { throw 'Enter the 16-character Google App Password (spaces are optional).' }
    $configDir = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\config'))
    [IO.Directory]::CreateDirectory($configDir) | Out-Null
    $localFile = Join-Path $configDir 'application-local.properties'
    $existing = if (Test-Path -LiteralPath $localFile) { @(Get-Content -LiteralPath $localFile) } else { @() }
    $settings = [ordered]@{
        'spring.mail.host' = 'smtp.gmail.com'
        'spring.mail.port' = '587'
        'spring.mail.username' = $SenderEmail
        'spring.mail.password' = $appPassword
        'spring.mail.properties.mail.smtp.auth' = 'true'
        'spring.mail.properties.mail.smtp.starttls.enable' = 'true'
        'spring.mail.properties.mail.smtp.starttls.required' = 'true'
        'spring.mail.properties.mail.smtp.ssl.checkserveridentity' = 'true'
        'smartcare.password-reset.from' = $SenderEmail
        'smartcare.password-reset.log-code-on-delivery-failure' = 'false'
    }
    $preserved = @($existing | Where-Object { ($_.Split('=', 2)[0]).Trim() -notin $settings.Keys })
    $updated = $preserved + @($settings.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" })
    [IO.File]::WriteAllLines($localFile, [string[]]$updated, [Text.UTF8Encoding]::new($false))
    Write-Host "Gmail sender configured: $SenderEmail. Restart the backend with the local profile."
    Write-Host 'Credentials are saved only in ignored config/application-local.properties.'
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    $securePassword.Dispose()
    $appPassword = $null
}
