@echo off
echo Setting up Fluxio for Windows...

:: Omogoči OpenSSH Server
powershell -Command "Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0"
powershell -Command "Start-Service sshd; Set-Service -Name sshd -StartupType 'Automatic'"

:: Odpri požarni zid za SSH in UDP Discovery
powershell -Command "New-NetFirewallRule -Name 'Fluxio-SSH' -DisplayName 'Fluxio SSH' -Enabled True -Direction Inbound -Protocol TCP -LocalPort 22 -Action Allow"
powershell -Command "New-NetFirewallRule -Name 'Fluxio-Discovery' -DisplayName 'Fluxio Discovery' -Enabled True -Direction Inbound -Protocol UDP -LocalPort 8888 -Action Allow"

:: UDP Handshake: Pošlji MAC naslov telefonu
echo Sending MAC address to Fluxio app...
powershell -Command "$mac = (Get-NetAdapter | Where-Object {$_.Status -eq 'Up'}).MacAddress; $udp = New-Object System.Net.Sockets.UdpClient; $byte = [System.Text.Encoding]::ASCII.GetBytes('FLUXIO_PAIR:' + $mac); $udp.Send($byte, $byte.Length, '255.255.255.255', 8888); $udp.Close()"

echo Done! Your PC is ready for Fluxio.
pause
