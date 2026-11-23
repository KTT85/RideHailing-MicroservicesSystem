param(
    [ValidateRange(1,1000)][int]$Count = 100,
    [string]$RabbitBase = 'http://localhost:15672',
    [string]$PaymentBase = 'http://localhost:8085',
    [int]$TimeoutSeconds = 120
)
$ErrorActionPreference = 'Stop'
foreach ($required in 'RABBITMQ_USER','RABBITMQ_PASSWORD','PAYMENT_INTERNAL_TOKEN') {
    if (![Environment]::GetEnvironmentVariable($required,'Process')) { throw "Set $required in this terminal first" }
}
$authBytes = [Text.Encoding]::UTF8.GetBytes($env:RABBITMQ_USER + ':' + $env:RABBITMQ_PASSWORD)
$headers = @{Authorization='Basic ' + [Convert]::ToBase64String($authBytes)}
$serviceHeaders = @{'X-Service-Token'=$env:PAYMENT_INTERNAL_TOKEN}
$passenger = [guid]::NewGuid().ToString()
$pending = [Collections.Generic.HashSet[string]]::new()
$latencies = [Collections.Generic.List[double]]::new()
$total = [Diagnostics.Stopwatch]::StartNew()
for ($i=0; $i -lt $Count; $i++) {
    $trip = [guid]::NewGuid().ToString()
    $event = @{eventId=[guid]::NewGuid().ToString();eventType='PaymentRequested';correlationId=$trip;timestamp=[DateTime]::UtcNow.ToString('o');payload=@{tripId=$trip;passengerId=$passenger;method='TRANSFER';amount=50000;currency='VND';idempotencyKey=$trip+':PAY'}}
    $body = @{properties=@{delivery_mode=2;content_type='application/json'};routing_key='PaymentRequested';payload=($event|ConvertTo-Json -Depth 10 -Compress);payload_encoding='string'} | ConvertTo-Json -Depth 10 -Compress
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $result = Invoke-RestMethod -Method Post -Uri "$RabbitBase/api/exchanges/%2F/ride.events/publish" -Headers $headers -ContentType 'application/json' -Body $body
    $timer.Stop(); $latencies.Add($timer.Elapsed.TotalMilliseconds)
    if (!$result.routed) { throw "Unroutable command for trip $trip" }
    [void]$pending.Add($trip)
}
$enqueueSeconds = $total.Elapsed.TotalSeconds
$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
while ($pending.Count -gt 0 -and [DateTime]::UtcNow -lt $deadline) {
    foreach ($trip in @($pending)) {
        try {
            $payment = Invoke-RestMethod -Uri "$PaymentBase/internal/payments/by-trip/$trip" -Headers $serviceHeaders
            if ($payment.status -eq 'SUCCESS') { [void]$pending.Remove($trip) }
        } catch {
            if (!$_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 404) { throw }
        }
    }
    if ($pending.Count -gt 0) { Start-Sleep -Milliseconds 250 }
}
$total.Stop()
$sorted = @($latencies | Sort-Object)
[pscustomobject]@{
    count=$Count;completed=$Count-$pending.Count;remaining=$pending.Count
    enqueueSeconds=[math]::Round($enqueueSeconds,3)
    completionSeconds=[math]::Round($total.Elapsed.TotalSeconds,3)
    completedPerSecond=[math]::Round(($Count-$pending.Count)/$total.Elapsed.TotalSeconds,2)
    publishP95Milliseconds=[math]::Round($sorted[[math]::Ceiling($Count*0.95)-1],2)
    timestampUtc=[DateTime]::UtcNow.ToString('o')
} | ConvertTo-Json
if ($pending.Count -gt 0) { throw 'Some payments did not reach SUCCESS before timeout' }
