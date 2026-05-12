# ==============================================================================
# FLUXIO CLEANUP & RESTORE SCRIPT (Windows)
# ==============================================================================

$FLUX_USER = "fluxio_user"

Write-Host "--- Fluxio System Cleanup ---" -ForegroundColor Yellow

# 1. Odstranitev fluxio_user uporabnika
Write-Host "[1/4] Removing Fluxio user..." -ForegroundColor White
if (Get-LocalUser | Where-Object { $_.Name -eq $FLUX_USER }) {
    Remove-LocalUser -Name $FLUX_USER
    Write-Host "User $FLUX_USER removed successfully." -ForegroundColor Green
} else {
    Write-Host "User $FLUX_USER not found. Skipping." -ForegroundColor Yellow
}

# 2. Povrnitev SSH konfiguracije (odstranitev AllowUsers)
Write-Host "[2/4] Restoring SSH configuration..." -ForegroundColor White
$sshdConfig = "$env:ProgramData\ssh\sshd_config"
if (Test-Path $sshdConfig) {
    $content = Get-Content $sshdConfig
    # Odstranimo vrstico AllowUsers fluxio_user
    $newContent = $content | Where-Object { $_ -notmatch "AllowUsers $FLUX_USER" }
    $newContent | Set-Content $sshdConfig -Encoding ascii
    Write-Host "SSH configuration cleaned." -ForegroundColor Green
}

# 3. Ustavitev in onemogočanje SSH strežnika
Write-Host "[3/4] Disabling SSH Server..." -ForegroundColor White
Stop-Service sshd -ErrorAction SilentlyContinue
Set-Service -Name sshd -StartupType 'Disabled'
Write-Host "SSH server stopped and disabled." -ForegroundColor Green

# 4. Ponastavitev požarnega zidu (opcijsko)
Write-Host "[4/4] Cleaning up firewall rules..." -ForegroundColor White
Remove-NetFirewallRule -DisplayName "OpenSSH Server (sshd)" -ErrorAction SilentlyContinue
Write-Host "Firewall rules removed." -ForegroundColor Green

Write-Host "`nCleanup complete. System restored to pre-Fluxio state." -ForegroundColor Cyan