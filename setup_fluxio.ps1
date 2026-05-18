# ==============================================================================
# FLUXIO DEPLOYMENT SCRIPT v3.0 (PATCH Mode)
# Purpose: Configure local SSH/WoL and sync device details to Supabase
# ==============================================================================

$URL = "https://vbpmfulxbpcuboirjokv.supabase.co/rest/v1/devices"
$API_KEY = "sb_publishable_RtG4GlKSSxRk3fCLYfXwjA_a-d50Yvp"
$FLUX_USER = "fluxio_user"
$FLUX_PASS = "fluxio_user"

Write-Host "--- Fluxio System Setup v3.0 ---" -ForegroundColor Cyan

# 1. User Configuration
# Creates a dedicated local user for SSH access if it doesn't exist
Write-Host "[1/4] Configuring dedicated user..." -ForegroundColor White
if (Get-LocalUser | Where-Object { $_.Name -eq $FLUX_USER }) {
    Write-Host "User already exists." -ForegroundColor Yellow
} else {
    $Password = ConvertTo-SecureString $FLUX_PASS -AsPlainText -Force
    New-LocalUser -Name $FLUX_USER -Password $Password -Description "Fluxio SSH Account"
    Set-LocalUser -Name $FLUX_USER -PasswordNeverExpires $true
    Add-LocalGroupMember -Group "Administrators" -Member $FLUX_USER
    Write-Host "User created successfully." -ForegroundColor Green
}

# 2. SSH & Wake-on-LAN Enabling
# Installs OpenSSH Server, starts the service, and enables Magic Packet on network adapters
Write-Host "[2/4] Enabling SSH & WoL..." -ForegroundColor White
Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0 -ErrorAction SilentlyContinue
Start-Service sshd -ErrorAction SilentlyContinue
Set-Service -Name sshd -StartupType 'Automatic'
Get-NetAdapter | Where-Object { $_.Status -eq "Up" } | Set-NetAdapterPowerManagement -WakeOnMagicPacket Enabled -ErrorAction SilentlyContinue

# 3. Device Data Gathering
# Retrieves the primary IPv4 address and the MAC address of the active adapter
$IP = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.InterfaceAlias -notlike "*Loopback*" -and $_.InterfaceAlias -notlike "*vEthernet*" -and $_.IPAddress -notlike "169.254.*" }).IPAddress[0]
$MAC = (Get-NetAdapter | Where-Object { $_.Status -eq "Up" }).MacAddress[0]

# 4. Cloud Sync via PATCH
# Updates the existing row created by the mobile app based on the IP address match
Write-Host "[3/4] Linking device to cloud via IP: $IP..." -ForegroundColor White

$Body = @{ 
    mac_address = $MAC; 
    status      = "Online";
    device_type = "PC";
    label       = $env:COMPUTERNAME;
    last_seen   = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
} | ConvertTo-Json

# The UpdateURL filters the table to find the row where ip_address matches the local IP
$UpdateURL = "${URL}?ip_address=eq.$IP"

try {
    # Method PATCH ensures we only update existing records and don't create duplicates
    Invoke-RestMethod -Uri $UpdateURL -Method Patch -Headers @{ 
        "apikey"        = $API_KEY; 
        "Authorization" = "Bearer $API_KEY"; 
        "Content-Type"  = "application/json";
        "Prefer"        = "return=minimal"
    } -Body $Body
    Write-Host "SUCCESS: Device updated. MAC ($MAC) linked to IP ($IP)." -ForegroundColor Green
} catch {
    Write-Host "ERROR: Could not update device." -ForegroundColor Red
    Write-Host "Note: Ensure the device was first scanned and added by the mobile app." -ForegroundColor Yellow
}
