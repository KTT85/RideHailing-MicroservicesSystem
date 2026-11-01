param(
    [string]$MySqlHost = 'localhost',
    [int]$MySqlPort = 3306,
    [string]$MySqlUser = 'user_app'
)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
& (Join-Path $PSScriptRoot 'use-local-jwt.ps1')
if ($env:MYSQL_USERNAME -and $env:MYSQL_USERNAME -ne $MySqlUser) { $env:MYSQL_PASSWORD = $null }
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:SERVER_PORT = '8081'
$env:MYSQL_HOST = $MySqlHost
$env:MYSQL_PORT = "$MySqlPort"
$env:MYSQL_USERNAME = $MySqlUser
if (!$env:MYSQL_PASSWORD) {
    $securedPassword = Read-Host "Mat khau MySQL cua $MySqlUser" -AsSecureString
    $env:MYSQL_PASSWORD = [Net.NetworkCredential]::new('', $securedPassword).Password
}
$cache = Join-Path $env:USERPROFILE '.m2/repository'
& mvn -B -ntp "-Dmaven.repo.local=$cache" -f (Join-Path $repoRoot 'src/user-service/pom.xml') spring-boot:run
if ($LASTEXITCODE -ne 0) { throw "User service exited with code $LASTEXITCODE" }
