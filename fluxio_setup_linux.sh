#!/bin/bash
echo "Setting up Fluxio for Linux..."

# Namesti OpenSSH če ga ni
sudo apt-get update && sudo apt-get install -y openssh-server net-tools

# Odpri port na UFW (če obstaja)
sudo ufw allow 22/tcp
sudo ufw allow 8888/udp

# Pridobi MAC in pošlji UDP Broadcast
MAC=$(ip link show $(ip route | awk '/default/ { print $5 }') | awk '/ether/ { print $2 }')
echo "Sending MAC address ($MAC) to Fluxio app..."
echo -n "FLUXIO_PAIR:$MAC" | socat - UDP4-DATAGRAM:255.255.255.255:8888,broadcast

echo "Done!"
