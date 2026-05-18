# ==============================================================================
# FLUXIO DEPLOYMENT SCRIPT v3.1 (Reliable IP Detection)
# ==============================================================================

$URL = "https://vbpmfulxbpcuboirjokv.supabase.co/rest/v1/devices"
$API_KEY = "sb_publishable_RtG4GlKSSxRk3fCLYfXwjA_a-d50Yvp"
$FLUX_USER = "fluxio_user"
$FLUX_PASS = "fluxio_user"

Write-Host "--- Fluxio System Setup v3.1 ---" -ForegroundColor Cyan

# 1. User Configuration
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
Write-Host "[2/4] Enabling SSH & WoL..." -ForegroundColor White
Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0 -ErrorAction SilentlyContinue
Start-Service sshd -ErrorAction SilentlyContinue
Set-Service -Name sshd -StartupType 'Automatic'
Get-NetAdapter | Where-Object { $_.Status -eq "Up" } | Set-NetAdapterPowerManagement -WakeOnMagicPacket Enabled -ErrorAction SilentlyContinue

# 3. Reliable IP and MAC Detection
# We filter out virtual adapters (like Hyper-V) to get the real local IP
$IP = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { 
    $_.InterfaceAlias -notlike "*Loopback*" -and 
    $_.InterfaceAlias -notlike "*vEthernet*" -and 
    $_.IPv4Address -notlike "169.254.*"
}).IPAddress | Select-Object -First 1

$MAC = (Get-NetAdapter | Where-Object { $_.Status -eq "Up" -and $_.HardwareInterface -eq $true }).MacAddress | Select-Object -First 1

if (-not $IP) {
    Write-Host "ERROR: Could not detect a valid local IP address." -ForegroundColor Red
    return
}

# 4. Cloud Sync via PATCH
Write-Host "[3/4] Linking device to cloud via IP: $IP..." -ForegroundColor White

$Body = @{ 
    mac_address = $MAC; 
    status      = "Online";
    device_type = "PC";
    label       = $env:COMPUTERNAME; # This will update the name in DB to your actual PC name
    last_seen   = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
} | ConvertTo-Json

# The UpdateURL filters the table to find the row where ip_address matches the local IP
$UpdateURL = "${URL}?ip_address=eq.$IP"

try {
    # Method PATCH updates the existing record found by the IP filter
    $response = Invoke-WebRequest -Uri $UpdateURL -Method Patch -Headers @{ 
        "apikey"        = $API_KEY; 
        "Authorization" = "Bearer $API_KEY"; 
        "Content-Type"  = "application/json";
        "Prefer"        = "return=representation" # Ask for data back to verify
    } -Body $Body
    
    if ($response.Content -eq "[]" -or -not $response.Content) {
        Write-Host "WARNING: No device with IP $IP found in database." -ForegroundColor Yellow
        Write-Host "Please scan the network with the mobile app first!" -ForegroundColor White
    } else {
        Write-Host "SUCCESS: Device updated. MAC ($MAC) linked to IP ($IP)." -ForegroundColor Green
    }
} catch {
    Write-Host "ERROR: Could not connect to Supabase." -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Yellow
}