#!/bin/bash
echo "Setting up Fluxio for macOS..."

# Omogoči Remote Login (SSH)
sudo systemsetup -setremotelogin on

# Pridobi MAC in pošlji UDP Broadcast
MAC=$(networksetup -getmacaddress en0 | awk '{print $3}')
echo "Sending MAC address ($MAC) to Fluxio app..."
echo -n "FLUXIO_PAIR:$MAC" | nc -u -b 255.255.255.255 8888

echo "Done!"
