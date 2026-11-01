# Foreground node runner launched in a hidden background host by start.ps1.
. (Join-Path $PSScriptRoot 'environment.ps1')
if (!(Test-Path -LiteralPath (Join-Path $rabbitSbin 'rabbitmq-server.bat'))) { throw 'Run setup.ps1 first' }
& (Join-Path $rabbitSbin 'rabbitmq-server.bat')
if ($LASTEXITCODE -ne 0) { throw "RabbitMQ exited with code $LASTEXITCODE" }
