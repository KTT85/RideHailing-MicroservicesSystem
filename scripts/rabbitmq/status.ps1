$ErrorActionPreference = 'Stop'
$headers = @{Authorization='Basic '+[Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('guest:guest'))}
$overview = Invoke-RestMethod 'http://127.0.0.1:15672/api/overview' -Headers $headers -TimeoutSec 3
Write-Host ('Node: '+$overview.node+' | RabbitMQ '+$overview.rabbitmq_version+' | http://localhost:15672')
$overview.listeners | Format-Table protocol,ip_address,port -AutoSize
$queues = Invoke-RestMethod 'http://127.0.0.1:15672/api/queues/%2F' -Headers $headers -TimeoutSec 3
$queues | Format-Table name,messages,consumers -AutoSize
