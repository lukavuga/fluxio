# ==============================================================================
# FLUXIO CLEANUP & RESTORE SCRIPT (v2.1)
# ==============================================================================

$FLUX_USER = "fluxio_user"

# Preverjanje skrbniških pravic
if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "NAPAKA: Skripto moraš zagnati kot ADMINISTRATOR!" -ForegroundColor Red
    exit
}

Write-Host "--- Fluxio System Cleanup & Restore ---" -ForegroundColor Yellow

# 1. Odstranitev fluxio_user uporabnika
Write-Host "[1/5] Removing Fluxio user..." -ForegroundColor White
if (Get-LocalUser | Where-Object { $_.Name -eq $FLUX_USER }) {
    try {
        Remove-LocalUser -Name $FLUX_USER -ErrorAction Stop
        Write-Host "SUCCESS: User $FLUX_USER removed." -ForegroundColor Green
    } catch {
        Write-Host "ERROR: Could not remove user. Is a session still active?" -ForegroundColor Red
    }
} else {
    Write-Host "User $FLUX_USER not found. Skipping." -ForegroundColor Yellow
}

# 2. Povrnitev SSH konfiguracije (sshd_config)
Write-Host "[2/5] Restoring SSH configuration..." -ForegroundColor White
$sshdConfig = "$env:ProgramData\ssh\sshd_config"
if (Test-Path $sshdConfig) {
    $content = Get-Content $sshdConfig
    # Odstranimo vrstico AllowUsers fluxio_user, ki jo doda setup skripta
    $newContent = $content | Where-Object { $_ -notmatch "AllowUsers $FLUX_USER" }
    $newContent | Set-Content $sshdConfig -Encoding ascii
    Write-Host "SUCCESS: sshd_config cleaned." -ForegroundColor Green
}

# 3. Ustavitev in onemogočanje SSH strežnika
Write-Host "[3/5] Disabling SSH Server..." -ForegroundColor White
Stop-Service sshd -ErrorAction SilentlyContinue
Set-Service -Name sshd -StartupType 'Disabled'
Write-Host "SUCCESS: SSH server stopped and disabled." -ForegroundColor Green

# 4. Ponastavitev požarnega zidu
Write-Host "[4/5] Cleaning up firewall rules..." -ForegroundColor White
# Odstranimo pravila za OpenSSH
Remove-NetFirewallRule -DisplayName "OpenSSH Server (sshd)" -ErrorAction SilentlyContinue
Write-Host "SUCCESS: Firewall rules removed." -ForegroundColor Green

# 5. Onemogočanje Wake-on-LAN (Povrnitev na privzeto)
Write-Host "[5/5] Resetting Wake-on-LAN settings..." -ForegroundColor White
Get-NetAdapter | Where-Object { $_.Status -eq "Up" } | Set-NetAdapterPowerManagement -WakeOnMagicPacket Disabled -ErrorAction SilentlyContinue
Write-Host "SUCCESS: WoL disabled on active adapters." -ForegroundColor Green

Write-Host "`nCleanup complete. System restored to pre-Fluxio state." -ForegroundColor Cyan
Write-Host "NOTE: The device entry remains in the Supabase database. Delete it manually via the app if needed." -ForegroundColor Gray
