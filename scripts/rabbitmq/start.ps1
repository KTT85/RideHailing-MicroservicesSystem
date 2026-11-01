$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'environment.ps1')
if (!(Test-Path -LiteralPath (Join-Path $rabbitSbin 'rabbitmq-server.bat'))) {
    & (Join-Path $PSScriptRoot 'setup.ps1')
}
$headers = @{Authorization='Basic '+[Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('guest:guest'))}
try {
    $overview = Invoke-RestMethod 'http://127.0.0.1:15672/api/overview' -Headers $headers -TimeoutSec 2
    if ($overview.node -ne $env:RABBITMQ_NODENAME) { throw 'Another RabbitMQ node is using these ports' }
    Write-Host ('RabbitMQ already running: '+$overview.node)
    & (Join-Path $PSScriptRoot 'initialize.ps1')
    return
} catch {
    if (Get-NetTCPConnection -LocalPort 5672,15672 -State Listen -ErrorAction SilentlyContinue) {
        throw 'Ports 5672/15672 are occupied or the broker is still starting. Run status.ps1.'
    }
}
$hostExe = (Get-Process -Id $PID).Path
$runner = Join-Path $PSScriptRoot 'run-node.ps1'
$outLog = Join-Path $env:RABBITMQ_BASE 'host.stdout.log'
$errLog = Join-Path $env:RABBITMQ_BASE 'host.stderr.log'
$process = Start-Process -FilePath $hostExe -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',('"'+$runner+'"')) -WorkingDirectory $repoRoot -WindowStyle Hidden -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru
$process.Id | Set-Content (Join-Path $env:RABBITMQ_BASE 'host.pid')
for ($attempt=0; $attempt -lt 50; $attempt++) {
    try {
        $overview = Invoke-RestMethod 'http://127.0.0.1:15672/api/overview' -Headers $headers -TimeoutSec 1
        if ($overview.node -eq $env:RABBITMQ_NODENAME) {
            Write-Host ('RabbitMQ '+$overview.rabbitmq_version+' running: '+$overview.node)
            Write-Host 'AMQP: 127.0.0.1:5672 | Management: http://localhost:15672 | local login: guest / guest'
            & (Join-Path $PSScriptRoot 'initialize.ps1')
            return
        }
    } catch { }
    $process.Refresh()
    if ($process.HasExited) { throw ('RabbitMQ failed. Inspect '+$errLog+' and '+$outLog) }
    Start-Sleep -Milliseconds 800
}
throw ('RabbitMQ did not become ready. Inspect '+$outLog)
