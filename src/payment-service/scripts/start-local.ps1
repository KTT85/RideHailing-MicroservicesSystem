param(
    [string]$MySqlHost = 'localhost',
    [int]$MySqlPort = 3306,
    [string]$MySqlUser = 'payment_app',
    [string]$MavenLocalRepository = (Join-Path $env:USERPROFILE '.m2/repository')
)
$ErrorActionPreference = 'Stop'
function Read-ProcessSecret([string]$Name, [string]$Prompt) {
    if (![Environment]::GetEnvironmentVariable($Name, 'Process')) {
        $secured = Read-Host $Prompt -AsSecureString
        $value = [System.Net.NetworkCredential]::new('', $secured).Password
        [Environment]::SetEnvironmentVariable($Name, $value, 'Process')
    }
}
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:MYSQL_HOST = $MySqlHost
$env:MYSQL_PORT = "$MySqlPort"
if ($env:MYSQL_USERNAME -and $env:MYSQL_USERNAME -ne $MySqlUser) {
    # A terminal previously used for User may still hold user_app's password.
    $env:MYSQL_PASSWORD = $null
}
$env:MYSQL_USERNAME = $MySqlUser
Read-ProcessSecret 'MYSQL_PASSWORD' 'Mat khau MySQL cua payment_app'
Read-ProcessSecret 'JWT_SECRET' 'JWT_SECRET Base64 (dung cung gia tri voi User service)'
try {
    $env:JWT_SECRET = $env:JWT_SECRET.Trim()
    $jwtKeyBytes = [Convert]::FromBase64String($env:JWT_SECRET)
    if ($jwtKeyBytes.Length -lt 32) { throw 'JWT key is too short' }
} catch {
    Remove-Item Env:JWT_SECRET -ErrorAction SilentlyContinue
    throw 'JWT_SECRET khong hop le: can Base64 cua it nhat 32 byte. Copy khoa tu terminal User, roi chay trong terminal Payment: $env:JWT_SECRET = (Get-Clipboard -Raw).Trim(). Sau do chay lai script. Khong dung accessToken.'
}
if (!$env:PAYMENT_INTERNAL_TOKEN) {
    $bytes = [byte[]]::new(32)
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $env:PAYMENT_INTERNAL_TOKEN = [Convert]::ToBase64String($bytes)
    Write-Host 'Da tao PAYMENT_INTERNAL_TOKEN trong terminal nay; giu cung gia tri khi chay Trip.'
}
$servicePom = Join-Path $PSScriptRoot '../pom.xml'
& mvn -B -ntp "-Dmaven.repo.local=$MavenLocalRepository" -f $servicePom spring-boot:run
if ($LASTEXITCODE -ne 0) { throw "Payment exited with code $LASTEXITCODE" }
