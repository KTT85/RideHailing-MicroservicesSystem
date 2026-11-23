param(
    [ValidateSet('Up', 'Status', 'Down', 'Config')][string]$Action = 'Up',
    [switch]$WithUser,
    [switch]$UserOnly
)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
if ($dockerCommand) { $dockerExe = $dockerCommand.Source } else {
    $dockerExe = Join-Path $env:LOCALAPPDATA 'Programs/DockerDesktop/resources/bin/docker.exe'
    if (!(Test-Path -LiteralPath $dockerExe)) {
        $dockerExe = 'C:/Program Files/Docker/Docker/resources/bin/docker.exe'
    }
}
if (!(Test-Path -LiteralPath $dockerExe)) { throw 'Install and start Docker Desktop first.' }
$previousPath = $env:PATH
$env:PATH = (Split-Path $dockerExe -Parent) + ';' + $env:PATH
$envFile = Join-Path $repoRoot '.env.docker-payment'
$previousEnvironment = @{}
function New-LocalSecret {
    $bytes = [byte[]]::new(32)
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes)
}
try {
    if (!(Test-Path -LiteralPath $envFile)) {
        if ($Action -ne 'Up') { throw 'Run this script with -Action Up first.' }
        $values = [ordered]@{RABBITMQ_USER='payment_local'; RABBITMQ_PASSWORD=(New-LocalSecret)}
        foreach ($account in 'ROOT','USER','DRIVER','TRIP','PAYMENT','NOTIFICATION') {
            $values["MYSQL_${account}_PASSWORD"] = New-LocalSecret
        }
        $values['JWT_SECRET'] = New-LocalSecret
        $values['PAYMENT_INTERNAL_TOKEN'] = New-LocalSecret
        $values['JWT_ISSUER'] = 'ds03-user-service'
        $values['PAYMENT_HTTP_PORT'] = '8085'
        $values['RABBITMQ_AMQP_PORT'] = '5672'
        $values['RABBITMQ_MANAGEMENT_PORT'] = '15672'
        $lines = @('# Local Docker credentials. Git-ignored; preserve for existing database volumes.')
        foreach ($key in $values.Keys) { $lines += "$key=$($values[$key])" }
        [IO.File]::WriteAllLines($envFile, $lines, [Text.UTF8Encoding]::new($false))
        Write-Host 'Created .env.docker-payment with random local credentials.'
    }
    foreach ($line in [IO.File]::ReadAllLines($envFile)) {
        if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
            $key = $Matches[1]
            $previousEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
            [Environment]::SetEnvironmentVariable($key, $Matches[2], 'Process')
        }
    }
    $composeArgs = @('compose','--project-name','htpt-payment','--env-file',$envFile,'-f',(Join-Path $repoRoot 'docker-compose.yml'))
    & $dockerExe @composeArgs config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Invalid Compose configuration.' }
    switch ($Action) {
        'Up' {
            $services = @('payment-service')
            if ($UserOnly) { $services = @('user-service') }
            elseif ($WithUser) { $services += 'user-service' }
            & $dockerExe @composeArgs up -d --build --wait --wait-timeout 300 @services
        }
        'Status' { & $dockerExe @composeArgs ps }
        'Down' { & $dockerExe @composeArgs down }
        'Config' { Write-Host 'Compose configuration is valid.' }
    }
    if ($LASTEXITCODE -ne 0) { throw "Docker action $Action failed. Inspect Docker Compose logs." }
} finally {
    foreach ($key in $previousEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($key, $previousEnvironment[$key], 'Process')
    }
    $env:PATH = $previousPath
}
