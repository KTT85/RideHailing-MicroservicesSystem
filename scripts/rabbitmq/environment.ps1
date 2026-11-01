$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$runtimeRoot = Join-Path $repoRoot '.local'
$env:ERLANG_HOME = Join-Path $runtimeRoot 'erlang'
$env:RABBITMQ_BASE = Join-Path $runtimeRoot 'rabbitmq-data'
$env:RABBITMQ_NODENAME = 'ds03@localhost'
$env:RABBITMQ_CONFIG_FILE = (Join-Path $repoRoot 'deploy/rabbitmq/rabbitmq').Replace('\','/')
$env:RABBITMQ_ENABLED_PLUGINS_FILE = (Join-Path $repoRoot 'deploy/rabbitmq/enabled_plugins').Replace('\','/')
$env:ERL_EPMD_ADDRESS = '127.0.0.1'
$erlangHomeDir = (Join-Path $runtimeRoot 'erlang-home').Replace('\','/')
$rabbitSbin = Join-Path $runtimeRoot 'rabbitmq/rabbitmq_server-4.3.6/sbin'
New-Item -ItemType Directory -Force $env:RABBITMQ_BASE,$erlangHomeDir | Out-Null
$cookieFile = Join-Path $env:RABBITMQ_BASE '.erlang.cookie'
if (!(Test-Path -LiteralPath $cookieFile)) {
    $bytes = [byte[]]::new(32)
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    ([BitConverter]::ToString($bytes).Replace('-','')) | Set-Content -LiteralPath $cookieFile -NoNewline
}
$cookie = (Get-Content -LiteralPath $cookieFile -Raw).Trim()
if ($cookie -notmatch '^[A-F0-9]{64}$') { throw 'Invalid local Erlang cookie' }
# Explicit cookie avoids OTP writing credentials into the user's global profile.
$env:RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS = '+S 2:2 -setcookie ' + $cookie
$env:RABBITMQ_CTL_ERL_ARGS = '+S 2:2 -setcookie ' + $cookie
$env:ERL_CRASH_DUMP = Join-Path $env:RABBITMQ_BASE 'erl_crash.dump'
