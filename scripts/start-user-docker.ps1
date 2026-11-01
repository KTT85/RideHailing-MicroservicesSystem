param([ValidateSet('Up', 'Status', 'Down', 'Config')][string]$Action = 'Up')
$ErrorActionPreference = 'Stop'
# Reuse Payment's Compose project, databases and local JWT key.
& (Join-Path $PSScriptRoot 'start-payment-docker.ps1') -Action $Action -UserOnly
