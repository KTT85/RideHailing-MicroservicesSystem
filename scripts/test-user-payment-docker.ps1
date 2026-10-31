$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$settings = @{}
foreach ($line in [IO.File]::ReadAllLines((Join-Path $repoRoot '.env.docker-payment'))) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $settings[$Matches[1]] = $Matches[2] }
}
$userPort = if ($settings.USER_HTTP_PORT) { $settings.USER_HTTP_PORT } else { '8081' }
$userBase = 'http://127.0.0.1:' + $userPort
$paymentBase = 'http://127.0.0.1:' + $settings.PAYMENT_HTTP_PORT
$rabbitBase = 'http://127.0.0.1:' + $settings.RABBITMQ_MANAGEMENT_PORT
function Http-Json([string]$Method, [string]$Uri, $Body, $Headers = @{}) {
    $arguments = @{Method=$Method;Uri=$Uri;Headers=$Headers}
    if ($null -ne $Body) { $arguments.ContentType='application/json'; $arguments.Body=($Body | ConvertTo-Json -Depth 10 -Compress) }
    Invoke-RestMethod @arguments
}
function Assert-HttpStatus([int]$Expected, [scriptblock]$Request) {
    try { & $Request | Out-Null } catch {
        if ($null -eq $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne $Expected) { throw }
        return
    }
    throw "Expected HTTP $Expected but request succeeded."
}
$randomBytes = [byte[]]::new(24)
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($randomBytes) } finally { $rng.Dispose() }
$password = [Convert]::ToBase64String($randomBytes)
function Register-Demo([string]$Role) {
    $suffix = [guid]::NewGuid().ToString('N')
    $email = "docker-$suffix@example.test"
    $registration = @{name='Docker demo';email=$email;phone=('0'+(Get-Random -Minimum 100000000 -Maximum 1000000000));password=$password;role=$Role}
    $user = Http-Json 'Post' "$userBase/auth/register" $registration
    $login = Http-Json 'Post' "$userBase/auth/login" @{identifier=$email;password=$password}
    if ($login.user.id -ne $user.id -or !$login.accessToken) { throw 'Registration/login mismatch.' }
    return @{User=$user;Headers=@{Authorization=('Bearer '+$login.accessToken)}}
}
if ((Http-Json 'Get' "$userBase/actuator/health" $null).status -ne 'UP') { throw 'User is not healthy.' }
$owner = Register-Demo 'PASSENGER'
$other = Register-Demo 'PASSENGER'
$driver = Register-Demo 'DRIVER'
$me = Http-Json 'Get' "$userBase/users/me" $null $owner.Headers
if ($me.id -ne $owner.User.id -or $me.password -or $me.passwordHash -or $me.password_hash) { throw 'Unexpected /users/me response.' }
Assert-HttpStatus 401 { Http-Json 'Get' "$userBase/users/me" $null }
Assert-HttpStatus 401 { Http-Json 'Post' "$userBase/auth/login" @{identifier=$owner.User.email;password='wrong-password'} }
$trip = [guid]::NewGuid().ToString()
$command = @{eventId=[guid]::NewGuid().ToString();eventType='PaymentRequested';correlationId=$trip;timestamp=[DateTime]::UtcNow.ToString('o');payload=@{tripId=$trip;passengerId=$owner.User.id;method='TRANSFER';amount=50000;currency='VND';idempotencyKey=$trip+':PAY'}}
$basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($settings.RABBITMQ_USER+':'+$settings.RABBITMQ_PASSWORD))
$rabbitHeaders = @{Authorization=('Basic '+$basic)}
$queue = 'user.payment.smoke.' + $trip
Http-Json 'Put' "$rabbitBase/api/queues/%2F/$queue" @{durable=$true;auto_delete=$false;arguments=@{}} $rabbitHeaders | Out-Null
try {
    Http-Json 'Post' "$rabbitBase/api/bindings/%2F/e/ride.events/q/$queue" @{routing_key='PaymentSucceeded';arguments=@{}} $rabbitHeaders | Out-Null
    $published = Http-Json 'Post' "$rabbitBase/api/exchanges/%2F/ride.events/publish" @{properties=@{delivery_mode=2;content_type='application/json'};routing_key='PaymentRequested';payload=($command | ConvertTo-Json -Depth 10 -Compress);payload_encoding='string'} $rabbitHeaders
    if (!$published.routed) { throw 'PAY command was not routed.' }
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    $view = $null
    do {
        try { $view = Http-Json 'Get' "$paymentBase/payments/by-trip/$trip" $null $owner.Headers }
        catch { if ([int]$_.Exception.Response.StatusCode -ne 404) { throw } }
        if ($view.status -eq 'SUCCESS') { break }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($view.status -ne 'SUCCESS' -or $view.passengerId -ne $owner.User.id) { throw 'Owner could not read successful payment.' }
    Assert-HttpStatus 401 { Http-Json 'Get' "$paymentBase/payments/by-trip/$trip" $null }
    Assert-HttpStatus 403 { Http-Json 'Get' "$paymentBase/payments/by-trip/$trip" $null $other.Headers }
    Assert-HttpStatus 403 { Http-Json 'Get' "$paymentBase/payments/by-trip/$trip" $null $driver.Headers }
    Write-Host "PASS: register/login/me; User JWT accepted by Payment; owner allowed, other passenger/driver denied. tripId=$trip"
} finally {
    Invoke-RestMethod -Method Delete -Uri "$rabbitBase/api/queues/%2F/$queue" -Headers $rabbitHeaders | Out-Null
}
