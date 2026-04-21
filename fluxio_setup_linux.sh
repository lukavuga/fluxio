#!/bin/bash
echo "[Fluxio] Starting Linux setup..."
sudo apt update && sudo apt install -y openssh-server ethtool
sudo systemctl enable --now ssh
sudo ufw allow ssh
INTERFACE=$(ip route get 8.8.8.8 | awk -- '{printf $5}')
sudo ethtool -s $INTERFACE wol g
echo "[Fluxio] Setup complete."
