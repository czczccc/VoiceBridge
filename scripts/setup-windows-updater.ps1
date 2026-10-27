param(
    [string]$Repository = "czczccc/VoiceBridge"
)
$ErrorActionPreference = "Stop"
# Creates the Tauri updater signing key once, uploads it as repository secrets and
# writes the matching public key into tauri.conf.json. Never print secret values.
Get-Command node -ErrorAction Stop | Out-Null
$gh = (Get-Command gh -ErrorAction SilentlyContinue).Source
if (-not $gh) {
    $candidate = Join-Path $env:ProgramFiles "GitHub CLI\gh.exe"
    if (Test-Path $candidate) { $gh = $candidate }
}
if (-not $gh) { throw "GitHub CLI (gh) not found. Install GitHub CLI first." }
& $gh auth status
if ($LASTEXITCODE -ne 0) { throw "Run gh auth login first." }

$folder = Join-Path $env:USERPROFILE ".voicebridge-signing"
$key = Join-Path $folder "windows-updater.key"
$publicFile = "$key.pub"
$passwordFile = Join-Path $folder "windows-updater-password.txt"
$cli = Join-Path $PSScriptRoot "..\windows\node_modules\.bin\tauri.cmd"
if ((Test-Path $key) -or (Test-Path $publicFile)) {
    throw "Updater key already exists in $folder. Reuse it; replacing it breaks updates for installed apps."
}
if (-not (Test-Path $cli)) {
    throw "Tauri CLI missing. Run 'npm install' inside windows/ first."
}
New-Item -ItemType Directory -Path $folder -Force | Out-Null
$identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls $folder /inheritance:r /grant:r "${identity}:(OI)(CI)F" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Cannot protect signing folder permissions." }
$random = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($random)
$rng.Dispose()
$password = [Convert]::ToBase64String($random)
$env:VOICEBRIDGE_UPDATER_PASSWORD = $password
try {
    & $cli signer generate -w $key -p $env:VOICEBRIDGE_UPDATER_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw "Updater key generation failed." }
    [IO.File]::WriteAllText($passwordFile, $password)
    $secrets = @{
        TAURI_SIGNING_PRIVATE_KEY = ([IO.File]::ReadAllText($key)).Trim()
        TAURI_SIGNING_PRIVATE_KEY_PASSWORD = $password
    }
    foreach ($name in $secrets.Keys) {
        $secrets[$name] | & $gh secret set $name --repo $Repository
        if ($LASTEXITCODE -ne 0) { throw "Failed to upload $name. Keep local key files for recovery." }
        Write-Host "Configured $name"
    }
    $publicKey = ([IO.File]::ReadAllText($publicFile)).Trim()
    if (-not $publicKey) { throw "Public key file is empty." }
    $configPath = Join-Path $PSScriptRoot "..\windows\src-tauri\tauri.conf.json"
    $config = [IO.File]::ReadAllText($configPath) | ConvertFrom-Json
    $config.plugins.updater.pubkey = $publicKey
    $json = ($config | ConvertTo-Json -Depth 20) + "`n"
    [IO.File]::WriteAllText($configPath, $json, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "Wrote updater public key into tauri.conf.json"
    Write-Host "Keep a secure backup of $folder. Never commit the private key."
} finally {
    Remove-Item Env:VOICEBRIDGE_UPDATER_PASSWORD -ErrorAction SilentlyContinue
}
