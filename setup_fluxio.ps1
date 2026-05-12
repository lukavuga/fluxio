# ==============================================================================
# FLUXIO DEPLOYMENT SCRIPT v2.1
# ==============================================================================

$URL = "https://vbpmfulxbpcuboirjokv.supabase.co/rest/v1/devices"
$API_KEY = "sb_publishable_RtG4GlKSSxRk3fCLYfXwjA_a-d50Yvp"
$FLUX_USER = "fluxio_user"
$FLUX_PASS = "fluxio_user"

Write-Host "--- Fluxio System Setup v2.1 ---" -ForegroundColor Cyan

# 1. Popravljeno ustvarjanje uporabnika (brez napak iz image_9b2790.png)
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

# 2. SSH & Wake-on-LAN
Write-Host "[2/4] Enabling SSH & WoL..." -ForegroundColor White
Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0 -ErrorAction SilentlyContinue
Start-Service sshd -ErrorAction SilentlyContinue
Set-Service -Name sshd -StartupType 'Automatic'
Get-NetAdapter | Where-Object { $_.Status -eq "Up" } | Set-NetAdapterPowerManagement -WakeOnMagicPacket Enabled -ErrorAction SilentlyContinue

# 3. Varovanje SSH (AllowUsers)
Write-Host "[3/4] Securing SSH..." -ForegroundColor White
$sshdConfig = "$env:ProgramData\ssh\sshd_config"
if ((Get-Content $sshdConfig) -notmatch "AllowUsers $FLUX_USER") {
    Add-Content $sshdConfig "`nAllowUsers $FLUX_USER"
    Restart-Service sshd
}

# 4. Sinhronizacija (UPSERT - deluje tudi brez predhodnega skeniranja)
Write-Host "[4/4] Syncing with Cloud..." -ForegroundColor White
$IP = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.InterfaceAlias -notlike "*Loopback*" -and $_.InterfaceAlias -notlike "*vEthernet*" -and $_.IPAddress -notlike "169.254.*" }).IPAddress[0]
$MAC = (Get-NetAdapter | Where-Object { $_.Status -eq "Up" }).MacAddress[0]

$Body = @{ 
    ip_address = $IP;
    mac_address = $MAC; 
    status = "Online";
    device_type = "PC";
    label = $env:COMPUTERNAME;
    last_seen = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
} | ConvertTo-Json

try {
    # UPORABI "Post" namesto "Patch" in dodaj "Prefer" glavo
    Invoke-RestMethod -Uri $URL -Method Post -Headers @{ 
        "apikey" = $API_KEY; 
        "Authorization" = "Bearer $API_KEY"; 
        "Content-Type" = "application/json";
        "Prefer" = "resolution=merge-duplicates"  # TA VRSTICA JE NAJPOMEMBNEJŠA
    } -Body $Body
    Write-Host "SUCCESS: PC $env:COMPUTERNAME synced ($IP)." -ForegroundColor Green
} catch {
    $err = $_.Exception.Message
    Write-Host "ERROR: Sync failed. Message: $err" -ForegroundColor Red
}