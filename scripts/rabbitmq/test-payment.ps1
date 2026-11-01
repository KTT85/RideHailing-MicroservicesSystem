$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'environment.ps1')
& (Join-Path $PSScriptRoot 'start.ps1')
$headers = @{Authorization='Basic '+[Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('guest:guest'))}
Invoke-RestMethod -Method Put 'http://127.0.0.1:15672/api/vhosts/payment-test' -Headers $headers -ContentType 'application/json' -Body '{}' | Out-Null
Invoke-RestMethod -Method Put 'http://127.0.0.1:15672/api/permissions/payment-test/guest' -Headers $headers -ContentType 'application/json' -Body '{"configure":".*","write":".*","read":".*"}' | Out-Null
$previousFlag = $env:PAYMENT_TEST_RABBIT
try {
    $env:PAYMENT_TEST_RABBIT = 'true'
    $pom = Join-Path $repoRoot 'src/payment-service/pom.xml'
    $mavenCache = Join-Path $env:USERPROFILE '.m2/repository'
    & mvn -B -ntp "-Dmaven.repo.local=$mavenCache" -f $pom '-Dtest=RabbitLiveIntegrationTest' test
    if ($LASTEXITCODE -ne 0) { throw "Payment live RabbitMQ tests failed: $LASTEXITCODE" }
} finally { $env:PAYMENT_TEST_RABBIT = $previousFlag }
