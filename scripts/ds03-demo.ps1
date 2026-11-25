[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('Up', 'Health', 'Driver', 'Race')]
    [string]$Stage,
    [switch]$EnableFailpoints,
    [ValidateRange(1, 65535)][int]$UserPort = 8081,
    [ValidateRange(1, 65535)][int]$PaymentPort = 8085,
    [ValidateRange(0, 300)][int]$WaitSeconds = 0
)

$ErrorActionPreference = 'Stop'
$root = (Get-Location).Path
$project = 'ds03-demo'
$userBase = "http://127.0.0.1:$UserPort"
$driverBase = 'http://127.0.0.1:8082'
$utf8 = [System.Text.UTF8Encoding]::new($false)
$services = [ordered]@{ User = $UserPort; Driver = 8082; Trip = 8083; Matching = 8084; Payment = $PaymentPort; Notification = 8086 }

function New-DemoSecret {
    $bytes = New-Object byte[] 32
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    [Convert]::ToBase64String($bytes)
}

function Assert-Equal($Actual, $Expected, [string]$Label) {
    if ($Actual -ne $Expected) {
        throw "$Label expected [$Expected], got [$Actual]"
    }
    Write-Host "PASS $Label = $Expected"
}

if ($Stage -eq 'Up') {
    if (-not (Test-Path -LiteralPath 'docker-compose.yml')) {
        throw 'Run Up from the HTPT-main repository root.'
    }
    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if ($docker) {
        $dockerCommand = $docker.Name
    } else {
        $dockerCommand = @(
            (Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\resources\bin\docker.exe')
            (Join-Path $env:ProgramFiles 'Docker\Docker\resources\bin\docker.exe')
        ) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
        if (-not $dockerCommand) { throw 'Docker CLI unavailable. Start Docker Desktop and reopen the terminal.' }
    }
    $envPath = Join-Path $root '.env'
    if (-not (Test-Path -LiteralPath $envPath)) {
        Copy-Item -LiteralPath '.env.example' -Destination $envPath
    }
    $settings = [IO.File]::ReadAllText($envPath)
    foreach ($key in @('JWT_SECRET', 'PAYMENT_INTERNAL_TOKEN', 'DRIVER_INTERNAL_TOKEN')) {
        $blank = [regex]::new("(?m)^$key=[ \t]*\r?$")
        if ($blank.IsMatch($settings)) {
            $settings = $blank.Replace($settings, "$key=$(New-DemoSecret)", 1)
        } elseif ($settings -notmatch "(?m)^$key=") {
            $settings = $settings.TrimEnd("`r", "`n") + "`r`n$key=$(New-DemoSecret)`r`n"
        }
    }
    $portSettings = @{ USER_HTTP_PORT = $UserPort; PAYMENT_HTTP_PORT = $PaymentPort }
    foreach ($key in $portSettings.Keys) {
        $entry = [regex]::new("(?m)^$key=[^\r\n]*")
        $line = "$key=$($portSettings[$key])"
        if ($entry.IsMatch($settings)) {
            $settings = $entry.Replace($settings, $line, 1)
        } else {
            $settings = $settings.TrimEnd("`r", "`n") + "`r`n$line`r`n"
        }
    }
    if ($settings -cne [IO.File]::ReadAllText($envPath)) {
        [IO.File]::WriteAllText($envPath, $settings, $utf8)
    }

    $override = Join-Path ([IO.Path]::GetTempPath()) "ds03-$([guid]::NewGuid()).yaml"
    $overrideText = @'
services:
  driver-service:
    environment:
      DRIVER_INTERNAL_TOKEN: ${DRIVER_INTERNAL_TOKEN:?Set DRIVER_INTERNAL_TOKEN}
  matching-service:
    environment:
      DRIVER_INTERNAL_TOKEN: ${DRIVER_INTERNAL_TOKEN:?Set DRIVER_INTERNAL_TOKEN}
'@
    $previousUserPort = $env:USER_HTTP_PORT
    $previousPaymentPort = $env:PAYMENT_HTTP_PORT
    $previousFailpoints = $env:DS03_ENABLE_DEMO_FAILPOINTS
    try {
        [IO.File]::WriteAllText($override, $overrideText, $utf8)
        # Compose gives process variables priority over .env.
        $env:USER_HTTP_PORT = [string]$UserPort
        $env:PAYMENT_HTTP_PORT = [string]$PaymentPort
        if ($EnableFailpoints) { $env:DS03_ENABLE_DEMO_FAILPOINTS = 'true' }
        & $dockerCommand compose -p $project -f docker-compose.yml -f $override up -d --build
        if ($LASTEXITCODE -ne 0) { throw 'Docker Compose startup failed.' }
    } finally {
        $env:USER_HTTP_PORT = $previousUserPort
        $env:PAYMENT_HTTP_PORT = $previousPaymentPort
        $env:DS03_ENABLE_DEMO_FAILPOINTS = $previousFailpoints
        if (Test-Path -LiteralPath $override) { Remove-Item -LiteralPath $override }
    }
    $startupWait = if ($WaitSeconds) { $WaitSeconds } else { 90 }
    & $PSCommandPath -Stage Health -UserPort $UserPort -PaymentPort $PaymentPort -WaitSeconds $startupWait
    return
}

Add-Type -AssemblyName System.Net.Http
$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(5)

function Start-DemoRequest([string]$Method, [string]$Uri, $Body = $null, [hashtable]$Headers = @{}) {
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), $Uri)
    foreach ($key in $Headers.Keys) { $request.Headers.Add($key, [string]$Headers[$key]) }
    if ($null -ne $Body) {
        $json = ConvertTo-Json -InputObject $Body -Depth 6 -Compress
        $request.Content = [System.Net.Http.StringContent]::new($json, $utf8, 'application/json')
    }
    [pscustomobject]@{ Request = $request; Task = $client.SendAsync($request); Label = "$Method $Uri" }
}

function Read-DemoResponse($Pending, [switch]$ReadBody) {
    $response = $null
    try {
        $response = $Pending.Task.GetAwaiter().GetResult()
        $data = $null
        if ($ReadBody -and $response.IsSuccessStatusCode) {
            $raw = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            if ($raw) { $data = ConvertFrom-Json -InputObject $raw }
        }
        [pscustomobject]@{ Code = [int]$response.StatusCode; Data = $data }
    } catch {
        $cause = $_.Exception.GetBaseException().Message
        throw "$($Pending.Label) failed: $cause. Check the service and port with docker compose -p ds03-demo ps -a."
    } finally {
        if ($response) { $response.Dispose() }
        $Pending.Request.Dispose()
    }
}

function Test-DemoHealth {
    $deadline = [DateTime]::UtcNow.AddSeconds($WaitSeconds)
    $ready = @{}
    do {
        foreach ($service in $services.Keys) {
            if ($ready.ContainsKey($service)) { continue }
            $port = $services[$service]
            try {
                $pending = Start-DemoRequest GET "http://127.0.0.1:$port/actuator/health"
                $result = Read-DemoResponse $pending -ReadBody
                if ($result.Code -eq 200 -and $result.Data.status -eq 'UP') {
                    $ready[$service] = $true
                    Write-Host "PASS $service on port $port = UP"
                }
            } catch {
                Write-Verbose $_.Exception.Message
            }
        }
        if ($ready.Count -eq $services.Count) { return }
        if ([DateTime]::UtcNow -lt $deadline) { Start-Sleep -Seconds 2 }
    } while ([DateTime]::UtcNow -lt $deadline)
    $missing = ($services.Keys | Where-Object { -not $ready.ContainsKey($_) } | ForEach-Object { "$_ ($($services[$_]))" }) -join ', '
    throw "Services not UP: $missing. Run docker compose -p ds03-demo ps -a and docker compose -p ds03-demo logs --tail 80."
}

function Invoke-DemoJson([string]$Method, [string]$Uri, $Body = $null, [hashtable]$Headers = @{}, [int]$Code = 200) {
    $pending = Start-DemoRequest $Method $Uri $Body $Headers
    $result = Read-DemoResponse $pending -ReadBody
    Assert-Equal $result.Code $Code $pending.Label
    $result.Data
}

function New-DemoAccount([string]$Role, [string]$Password) {
    $email = "ds03-$([guid]::NewGuid().ToString('N'))@example.com"
    $phone = '09' + (Get-Random -Minimum 10000000 -Maximum 100000000)
    $null = Invoke-DemoJson POST "$userBase/auth/register" @{
        name = "Demo $Role"; email = $email; phone = $phone; password = $Password; role = $Role
    } -Code 201
    $session = Invoke-DemoJson POST "$userBase/auth/login" @{ identifier = $email; password = $Password }
    Assert-Equal $session.user.role $Role 'JWT account role'
    if (-not $session.accessToken) { throw 'Login response has no accessToken.' }
    @{ Authorization = "Bearer $($session.accessToken)" }
}

function Test-DriverRace($DriverProfile, [hashtable]$DriverHeaders, [string]$ServiceToken) {
    $tripA = [guid]::NewGuid().ToString()
    $tripB = [guid]::NewGuid().ToString()
    $route = "$driverBase/internal/drivers/$($DriverProfile.driverId)"
    $serviceHeaders = @{ 'X-Service-Token' = $ServiceToken }
    $requestA = Start-DemoRequest POST "$route/reserve" @{ tripId = $tripA } $serviceHeaders
    $requestB = Start-DemoRequest POST "$route/reserve" @{ tripId = $tripB } $serviceHeaders
    $resultA = Read-DemoResponse $requestA
    $resultB = Read-DemoResponse $requestB
    Write-Host "Trip A $tripA HTTP $($resultA.Code)"
    Write-Host "Trip B $tripB HTTP $($resultB.Code)"

    $current = Invoke-DemoJson GET "$driverBase/drivers/me" -Headers $DriverHeaders
    try {
        Assert-Equal ((@($resultA.Code, $resultB.Code) | Sort-Object) -join ',') '200,409' 'Reserve race HTTP codes'
        $winner = if ($resultA.Code -eq 200) { $tripA } else { $tripB }
        Assert-Equal $current.status 'RESERVED' 'Driver state after race'
        Assert-Equal $current.currentTripId $winner 'Only winning trip holds the driver'
    } finally {
        if ($current.currentTripId -in @($tripA, $tripB)) {
            $released = Invoke-DemoJson POST "$route/release" @{ tripId = $current.currentTripId } $serviceHeaders
            Assert-Equal $released.status 'AVAILABLE' 'Release returns driver to AVAILABLE'
        }
    }
}

try {
    if ($Stage -eq 'Health') {
        Test-DemoHealth
    } else {
        $driverServiceToken = $env:DRIVER_INTERNAL_TOKEN
        if ($Stage -eq 'Race' -and -not $driverServiceToken) {
            $line = Get-Content -LiteralPath '.env' | Where-Object { $_ -match '^DRIVER_INTERNAL_TOKEN=' } | Select-Object -First 1
            if ($line) { $driverServiceToken = ($line -split '=', 2)[1].Trim().Trim('"', "'") }
            if (-not $driverServiceToken) { throw 'Run Up first to configure DRIVER_INTERNAL_TOKEN.' }
        }
        $password = New-DemoSecret
        $driverHeaders = New-DemoAccount DRIVER $password
        $plate = '51A-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
        $profile = Invoke-DemoJson POST "$driverBase/drivers/profile" @{
            licensePlate = $plate; vehicleType = 'BIKE'
        } $driverHeaders -Code 201
        Assert-Equal $profile.status 'OFFLINE' 'Initial driver state'
        $null = Invoke-DemoJson PUT "$driverBase/drivers/me/location" @{
            latitude = 10.7626224; longitude = 106.6601724
        } $driverHeaders
        $available = Invoke-DemoJson PATCH "$driverBase/drivers/me/availability" @{ acceptingTrips = $true } $driverHeaders
        try {
            Assert-Equal $available.status 'AVAILABLE' 'Driver is ready'
            Write-Host "driverId=$($profile.driverId)"
            if ($Stage -eq 'Race') {
                Test-DriverRace $profile $driverHeaders $driverServiceToken
            } else {
                $passengerHeaders = New-DemoAccount PASSENGER $password
                $null = Invoke-DemoJson GET "$userBase/users/me" -Headers $passengerHeaders
                $null = Invoke-DemoJson GET "$userBase/users/me" -Code 401
                $null = Invoke-DemoJson GET "$driverBase/drivers/me" -Headers $passengerHeaders -Code 403
                $null = Invoke-DemoJson PUT "$driverBase/drivers/me/location" @{
                    latitude = 91; longitude = 106.66
                } $driverHeaders -Code 400
            }
        } finally {
            $last = Invoke-DemoJson GET "$driverBase/drivers/me" -Headers $driverHeaders
            if ($last.status -eq 'AVAILABLE') {
                $offline = Invoke-DemoJson PATCH "$driverBase/drivers/me/availability" @{ acceptingTrips = $false } $driverHeaders
                Assert-Equal $offline.status 'OFFLINE' 'Demo cleanup'
            }
        }
    }
} finally {
    $client.Dispose()
}
