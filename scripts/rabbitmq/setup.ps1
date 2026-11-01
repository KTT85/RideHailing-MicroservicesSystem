$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'environment.ps1')
$downloads = Join-Path $runtimeRoot 'downloads'
New-Item -ItemType Directory -Force $downloads | Out-Null
$packages = @(
    @{Name='otp_win64_28.5.zip';Url='https://github.com/erlang/otp/releases/download/OTP-28.5/otp_win64_28.5.zip';Hash='d3227232221e68aa8790666e915604269d5471b61792a5d78a043ac653351140';Destination=(Join-Path $runtimeRoot 'erlang');Marker=(Join-Path $runtimeRoot 'erlang/bin/erl.exe')},
    @{Name='rabbitmq-server-windows-4.3.6.zip';Url='https://github.com/rabbitmq/rabbitmq-server/releases/download/v4.3.6/rabbitmq-server-windows-4.3.6.zip';Hash='48161b084f1257edc112b24fccda378cc3e83c8e4b94984801cd6651817981f1';Destination=(Join-Path $runtimeRoot 'rabbitmq');Marker=(Join-Path $rabbitSbin 'rabbitmq-server.bat')}
)
foreach($package in $packages) {
    $archive = Join-Path $downloads $package.Name
    if (!(Test-Path -LiteralPath $archive)) {
        Write-Host ('Downloading official package: ' + $package.Name)
        Invoke-WebRequest -Uri $package.Url -OutFile $archive
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $package.Hash) {
        throw ('SHA256 verification failed: ' + $archive)
    }
    if (!(Test-Path -LiteralPath $package.Marker)) {
        Expand-Archive -LiteralPath $archive -DestinationPath $package.Destination
    }
    Write-Host ('Verified: ' + $package.Name)
}
Write-Host 'Ready. Start with: ./scripts/rabbitmq/start.ps1'
