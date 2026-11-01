param(
    [string]$MySqlHost = 'localhost',
    [int]$MySqlPort = 3306,
    [string]$MySqlUser = 'payment_app'
)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
& (Join-Path $PSScriptRoot 'use-local-jwt.ps1')
$env:SERVER_PORT = '8085'
& (Join-Path $PSScriptRoot 'rabbitmq/start.ps1')
$env:RABBITMQ_HOST = '127.0.0.1'
$env:RABBITMQ_PORT = '5672'
$env:RABBITMQ_USER = 'guest'
$env:RABBITMQ_PASSWORD = 'guest'
& (Join-Path $repoRoot 'src/payment-service/scripts/start-local.ps1') -MySqlHost $MySqlHost -MySqlPort $MySqlPort -MySqlUser $MySqlUser
