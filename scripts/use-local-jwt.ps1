# One stable, per-Windows-user development key for local User and Payment.
# DPAPI encrypts the saved value; no key is printed or placed on the clipboard.
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$secretDir = Join-Path $repoRoot '.local'
$secretFile = Join-Path $secretDir 'jwt-secret.dpapi'
New-Item -ItemType Directory -Force $secretDir | Out-Null
if (Test-Path -LiteralPath $secretFile) {
    $securedKey = ConvertTo-SecureString -String (Get-Content -LiteralPath $secretFile -Raw).Trim()
    $localKey = [Net.NetworkCredential]::new('', $securedKey).Password
} else {
    $localKey = $env:JWT_SECRET
    $validKey = $false
    try { $validKey = [Convert]::FromBase64String($localKey).Length -ge 32 } catch { }
    if (!$validKey) {
        $keyBytes = New-Object byte[] 32
        $random = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $random.GetBytes($keyBytes) } finally { $random.Dispose() }
        $localKey = [Convert]::ToBase64String($keyBytes)
    }
    $encryptedKey = $localKey | ConvertTo-SecureString -AsPlainText -Force | ConvertFrom-SecureString
    Set-Content -LiteralPath $secretFile -Value $encryptedKey -NoNewline
}
if ([Convert]::FromBase64String($localKey).Length -lt 32) { throw 'Saved local JWT key is invalid' }
$env:JWT_SECRET = $localKey
Write-Host 'Loaded shared local JWT key (encrypted on disk).'
