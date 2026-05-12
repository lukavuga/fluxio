# ==============================================================================
# FLUXIO DEPLOYMENT SCRIPT (Windows - v2.0)
# ==============================================================================

$URL = "https://vbpmfulxbpcuboirjokv.supabase.co/rest/v1/devices"
$API_KEY = "sb_publishable_RtG4GlKSSxRk3fCLYfXwjA_a-d50Yvp"
$FLUX_USER = "fluxio_user"
$FLUX_PASS = "fluxio_user"

Write-Host "--- Fluxio System Setup ---" -ForegroundColor Cyan

# 1. Ustvarjanje fluxio_user uporabnika
Write-Host "[1/5] Creating dedicated Fluxio user..." -ForegroundColor White
if (Get-LocalUser | Where-Object { $_.Name -eq $FLUX_USER }) {
    Write-Host "User already exists." -ForegroundColor Yellow
} else {
    $Password = ConvertTo-SecureString $FLUX_PASS -AsPlainText -Force
    New-LocalUser -Name $FLUX_USER -Password $Password -Description "Used for Fluxio SSH Shutdown" -PasswordNeverExpires $true
    Add-LocalGroupMember -Group "Administrators" -Member $FLUX_USER # Potrebno za ukaz 'shutdown'
    Write-Host "User created successfully." -ForegroundColor Green
}

# 2. Omogočanje SSH strežnika
Write-Host "[2/5] Configuring SSH Server..." -ForegroundColor White
Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0 -ErrorAction SilentlyContinue
Start-Service sshd -ErrorAction SilentlyContinue
Set-Service -Name sshd -StartupType 'Automatic'

# 3. Omejitev SSH samo na fluxio_user
Write-Host "[3/5] Securing SSH access..." -ForegroundColor White
$sshdConfig = "$env:ProgramData\ssh\sshd_config"
if (Test-Path $sshdConfig) {
    $content = Get-Content $sshdConfig
    if ($content -notmatch "AllowUsers $FLUX_USER") {
        Add-Content $sshdConfig "`nAllowUsers $FLUX_USER"
        Restart-Service sshd
    }
}

# 4. Omogočanje Wake-on-LAN
Write-Host "[4/5] Configuring Wake-on-LAN..." -ForegroundColor White
Get-NetAdapter | Where-Object { $_.Status -eq "Up" } | Set-NetAdapterPowerManagement -WakeOnMagicPacket Enabled -ErrorAction SilentlyContinue

# 5. Sinhronizacija s Cloudom
Write-Host "[5/5] Synchronizing with Fluxio Cloud..." -ForegroundColor White
$IP = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.InterfaceAlias -notlike "*Loopback*" -and $_.InterfaceAlias -notlike "*vEthernet*" -and $_.IPAddress -notlike "169.254.*" }).IPAddress[0]
$MAC = (Get-NetAdapter | Where-Object { $_.Status -eq "Up" }).MacAddress[0]

$Body = @{ 
    mac_address = $MAC; 
    status = "Online";
    device_type = "PC";
    last_seen = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
} | ConvertTo-Json

$TargetURL = "$URL?ip_address=eq.$IP"
try {
    Invoke-RestMethod -Uri $TargetURL -Method Patch -Headers @{ "apikey" = $API_KEY; "Authorization" = "Bearer $API_KEY"; "Content-Type" = "application/json" } -Body $Body
    Write-Host "SUCCESS: PC synced via $IP ($MAC). fluxio_user is ready." -ForegroundColor Green
} catch {
    Write-Host "ERROR: Device not found in DB. Scan in the app first!" -ForegroundColor Red
}