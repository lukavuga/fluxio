# ==============================================================================
# FLUXIO DEPLOYMENT SCRIPT (Windows)
# ------------------------------------------------------------------------------
# IMPORTANT: This script enables WoL in Windows, but it CANNOT enable it in the 
# system BIOS/UEFI. You must MANUALLY enable "Wake-on-LAN", "Power On By PCI-E" 
# or "Magic Packet Wake Up" in the BIOS settings for this to work.
#
# NOTE: While a single execution activates SSH/WoL, we RECOMMENDED setting 
# this script to run at every system startup (via Task Scheduler or Startup folder). 
# This ensures the Fluxio App always displays the current IP address.
# ==============================================================================

$URL = "https://vbpmfulxbpcuboirjokv.supabase.co/rest/v1/devices"
$API_KEY = "sb_publishable_RtG4GlKSSxRk3fCLYfXwjA_a-d50Yvp"

Write-Host "--- Fluxio System Setup ---" -ForegroundColor Cyan

# 1. Enable SSH Server
Write-Host "[1/3] Configuring SSH..." -ForegroundColor White
Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0 -ErrorAction SilentlyContinue
Start-Service sshd -ErrorAction SilentlyContinue
Set-Service -Name sshd -StartupType 'Automatic'

# 2. Enable Wake-on-LAN in Network Adapter
Write-Host "[2/3] Configuring Wake-on-LAN..." -ForegroundColor White
Get-NetAdapter | Where-Object { $_.Status -eq "Up" } | Set-NetAdapterPowerManagement -WakeOnMagicPacket Enabled -ErrorAction SilentlyContinue

# 3. Sync Data to Supabase
Write-Host "[3/3] Synchronizing with Fluxio Cloud..." -ForegroundColor White
$IP = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.InterfaceAlias -notlike "*Loopback*" -and $_.IPAddress -notlike "169.254.*" }).IPAddress[0]
$MAC = (Get-NetAdapter | Where-Object { $_.Status -eq "Up" }).MacAddress

$Body = @{ 
    mac_address = $MAC; 
    status = "Online";
    last_seen = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
} | ConvertTo-Json

$TargetURL = "$URL?ip_address=eq.$IP"

try {
    Invoke-RestMethod -Uri $TargetURL -Method Patch -Headers @{ 
        "apikey" = $API_KEY; 
        "Authorization" = "Bearer $API_KEY"; 
        "Content-Type" = "application/json"
    } -Body $Body
    Write-Host "SUCCESS: Device synced via IP $IP" -ForegroundColor Green
} catch {
    Write-Host "ERROR: Could not sync. Make sure the device is first saved in the app." -ForegroundColor Red
}