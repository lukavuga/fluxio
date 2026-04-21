@echo off
echo [Fluxio] Starting automated Windows setup...
powershell.exe -Command "Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0"
powershell.exe -Command "Set-Service -Name sshd -StartupType 'Automatic'; Start-Service sshd"
netsh advfirewall firewall add rule name="Fluxio_SSH" dir=in action=allow protocol=TCP localport=22
powershell.exe -Command "Get-NetAdapter | Set-NetAdapterPowerManagement -WakeOnMagicPacket Enabled"
echo [Fluxio] Setup complete. Your PC is ready.
pause
