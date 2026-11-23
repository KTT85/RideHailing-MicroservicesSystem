$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$headers = @{Authorization='Basic '+[Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('guest:guest'))}
$definitions = Get-Content -LiteralPath (Join-Path $repoRoot 'deploy/rabbitmq/definitions.json') -Raw
Invoke-RestMethod -Method Post 'http://127.0.0.1:15672/api/definitions' -Headers $headers -ContentType 'application/json' -Body $definitions | Out-Null
Write-Host 'Declared ride.events, payment.delivery, payment.commands, payment.retry and payment.dlq in vhost /.'
