param(
    [string]$Repository = "czczccc/VoiceBridge"
)
$ErrorActionPreference = "Stop"
# Requires JDK keytool and authenticated GitHub CLI. Never print secret values.
Get-Command keytool -ErrorAction Stop | Out-Null
Get-Command gh -ErrorAction Stop | Out-Null
& gh auth status
if ($LASTEXITCODE -ne 0) { throw "Run gh auth login first." }

$folder = Join-Path $env:USERPROFILE ".voicebridge-signing"
$keystore = Join-Path $folder "android-testing.jks"
$passwordFile = Join-Path $folder "password.txt"
if ((Test-Path $keystore) -or (Test-Path $passwordFile)) {
    throw "Signing files already exist in $folder. Preserve them; do not replace an existing signing key."
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
[IO.File]::WriteAllText($passwordFile, $password)
$env:VOICEBRIDGE_SIGNING_PASSWORD = $password
try {
    & keytool -genkeypair -keystore $keystore -storetype JKS -alias voicebridge-testing `
        -keyalg RSA -keysize 3072 -validity 10000 `
        -dname "CN=VoiceBridge Testing" `
        -storepass:env VOICEBRIDGE_SIGNING_PASSWORD -keypass:env VOICEBRIDGE_SIGNING_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw "Key generation failed." }
    $secrets = @{
        ANDROID_KEYSTORE_BASE64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore))
        ANDROID_KEYSTORE_PASSWORD = $password
        ANDROID_KEY_ALIAS = "voicebridge-testing"
        ANDROID_KEY_PASSWORD = $password
    }
    foreach ($name in $secrets.Keys) {
        $secrets[$name] | & gh secret set $name --repo $Repository
        if ($LASTEXITCODE -ne 0) { throw "Failed to upload $name. Keep local signing files for recovery." }
        Write-Host "Configured $name"
    }
    Write-Host "Signing configured. Keep a secure backup of $folder. Never commit these files."
} finally {
    Remove-Item Env:VOICEBRIDGE_SIGNING_PASSWORD -ErrorAction SilentlyContinue
}
