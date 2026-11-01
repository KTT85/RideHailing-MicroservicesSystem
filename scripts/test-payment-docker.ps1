$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$settings = @{}
foreach ($line in [IO.File]::ReadAllLines((Join-Path $repoRoot '.env.docker-payment'))) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $settings[$Matches[1]] = $Matches[2] }
}
$paymentBase = 'http://127.0.0.1:' + $settings.PAYMENT_HTTP_PORT
$rabbitBase = 'http://127.0.0.1:' + $settings.RABBITMQ_MANAGEMENT_PORT
$basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($settings.RABBITMQ_USER + ':' + $settings.RABBITMQ_PASSWORD))
$rabbitHeaders = @{Authorization = 'Basic ' + $basic}
$serviceHeaders = @{'X-Service-Token' = $settings.PAYMENT_INTERNAL_TOKEN}
$trip = [guid]::NewGuid().ToString()
$queue = 'payment.docker.smoke.' + $trip
function Rabbit-Request([string]$Method, [string]$Path, $Body) {
    Invoke-RestMethod -Method $Method -Uri ($rabbitBase + '/api/' + $Path) -Headers $rabbitHeaders -ContentType 'application/json' -Body ($Body | ConvertTo-Json -Depth 15 -Compress)
}
function Send-Command($Command) {
    $result = Rabbit-Request 'Post' 'exchanges/%2F/ride.events/publish' @{
        properties = @{delivery_mode=2;content_type='application/json'}
        routing_key = $Command.eventType
        payload = ($Command | ConvertTo-Json -Depth 10 -Compress)
        payload_encoding = 'string'
    }
    if (!$result.routed) { throw 'Command was not routed.' }
}
function Wait-Payment([string]$Expected, [int]$OperationCount) {
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    while ([DateTime]::UtcNow -lt $deadline) {
        try { $view = Invoke-RestMethod -Uri "$paymentBase/internal/payments/by-trip/$trip" -Headers $serviceHeaders }
        catch { if ([int]$_.Exception.Response.StatusCode -ne 404) { throw }; $view = $null }
        if ($view.status -eq $Expected -and @($view.operations).Count -eq $OperationCount) { return $view }
        Start-Sleep -Milliseconds 300
    }
    throw "Payment did not reach $Expected with $OperationCount operations."
}
$health = Invoke-RestMethod "$paymentBase/actuator/health"
if ($health.status -ne 'UP') { throw 'Payment is not healthy.' }
Rabbit-Request 'Put' "queues/%2F/$queue" @{durable=$true;auto_delete=$false;arguments=@{}} | Out-Null
# Topic keys have one segment; # observes result events and commands in this isolated demo queue.
Rabbit-Request 'Post' "bindings/%2F/e/ride.events/q/$queue" @{routing_key='#';arguments=@{}} | Out-Null
try {
    $command = @{eventId=[guid]::NewGuid().ToString();eventType='PaymentRequested';correlationId=$trip;timestamp=[DateTime]::UtcNow.ToString('o');payload=@{tripId=$trip;passengerId=[guid]::NewGuid().ToString();method='TRANSFER';amount=50000;currency='VND';idempotencyKey=$trip+':PAY'}}
    Send-Command $command
    $view = Wait-Payment 'SUCCESS' 1
    Send-Command $command
    $command.eventId = [guid]::NewGuid().ToString()
    $command.eventType = 'PaymentRefundRequested'
    $command.payload.idempotencyKey = $trip + ':REFUND'
    Send-Command $command
    $view = Wait-Payment 'REFUNDED' 2
    if ($view.refundedAmount -ne 50000) { throw 'Unexpected refund amount.' }
    $seen = @{}
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $messages = Rabbit-Request 'Post' "queues/%2F/$queue/get" @{count=100;ackmode='ack_requeue_false';encoding='auto';truncate=50000}
        foreach ($message in $messages) {
            $event = $message.payload | ConvertFrom-Json
            if ($event.payload.tripId -eq $trip -and $event.eventType -in @('PaymentSucceeded','PaymentRefunded')) { $seen[$event.eventId] = $event.eventType }
        }
        if (@($seen.Values | Where-Object { $_ -eq 'PaymentSucceeded' }).Count -ge 1 -and @($seen.Values | Where-Object { $_ -eq 'PaymentRefunded' }).Count -ge 1) { break }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($seen.Count -ne 2) { throw 'Expected one unique success and one unique refund event.' }
    Write-Host "PASS: Docker Payment + MySQL + RabbitMQ; PAY, duplicate PAY, REFUND and published results. tripId=$trip"
} finally {
    Invoke-RestMethod -Method Delete -Uri "$rabbitBase/api/queues/%2F/$queue" -Headers $rabbitHeaders | Out-Null
}
