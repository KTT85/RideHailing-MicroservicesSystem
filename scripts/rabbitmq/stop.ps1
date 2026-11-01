. (Join-Path $PSScriptRoot 'environment.ps1')
& (Join-Path $rabbitSbin 'rabbitmqctl.bat') -n $env:RABBITMQ_NODENAME stop
if ($LASTEXITCODE -ne 0) { throw "RabbitMQ stop failed: $LASTEXITCODE" }
