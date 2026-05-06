# ==============================================================================
# FLUXIO DEPLOYMENT SCRIPT (Windows - Optimized)
# ==============================================================================

$URL = "https://vbpmfulxbpcuboirjokv.supabase.co/rest/v1/devices"
$API_KEY = "sb_publishable_RtG4GlKSSxRk3fCLYfXwjA_a-d50Yvp"

Write-Host "--- Fluxio System Setup ---" -ForegroundColor Cyan

# 1. Omogočanje SSH strežnika
Write-Host "[1/3] Configuring SSH..." -ForegroundColor White
Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0 -ErrorAction SilentlyContinue
Start-Service sshd -ErrorAction SilentlyContinue
Set-Service -Name sshd -StartupType 'Automatic'

# 2. Omogočanje Wake-on-LAN na mrežni kartici
Write-Host "[2/3] Configuring Wake-on-LAN..." -ForegroundColor White
Get-NetAdapter | Where-Object { $_.Status -eq "Up" } | Set-NetAdapterPowerManagement -WakeOnMagicPacket Enabled -ErrorAction SilentlyContinue

# 3. Pridobivanje podatkov naprave
Write-Host "[3/3] Synchronizing with Fluxio Cloud..." -ForegroundColor White

# Pridobimo IP (izogibamo se virtualnim adapterjem)
$IP = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { 
    $_.InterfaceAlias -notlike "*Loopback*" -and 
    $_.InterfaceAlias -notlike "*vEthernet*" -and 
    $_.IPAddress -notlike "169.254.*" 
}).IPAddress[0]

# POPRAVEK: Pridobimo samo en MAC naslov (prvi aktivni)
$MAC = (Get-NetAdapter | Where-Object { $_.Status -eq "Up" }).MacAddress[0]

# Priprava telesa za PATCH (posodobitev obstoječe vrstice po IP-ju)
$Body = @{ 
    mac_address = $MAC; 
    status = "Online";
    device_type = "Computer"; # Ker se ta skripta izvaja na Windowsu, je to PC
    last_seen = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
} | ConvertTo-Json

# Naslovimo napravo v bazi preko njenega IP naslova
$TargetURL = "$URL?ip_address=eq.$IP"

try {
    # Uporabimo PATCH, da ne ustvarjamo novih vrstic, ampak samo dopolnimo tisto,
    # ki jo je ustvarila aplikacija med skeniranjem.
    Invoke-RestMethod -Uri $TargetURL -Method Patch -Headers @{ 
        "apikey" = $API_KEY; 
        "Authorization" = "Bearer $API_KEY"; 
        "Content-Type" = "application/json"
    } -Body $Body
    Write-Host "SUCCESS: Device synced via IP $IP ($MAC)" -ForegroundColor Green
} catch {
    Write-Host "ERROR: Device with IP $IP not found. Make sure to SCAN and SAVE the network in the app first." -ForegroundColor Red
}