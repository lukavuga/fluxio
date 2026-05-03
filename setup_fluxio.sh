#!/bin/bash

# ==============================================================================
# FLUXIO DEPLOYMENT SCRIPT (Linux)
# ------------------------------------------------------------------------------
# IMPORTANT: This script configures the OS for Wake-on-LAN. However, WoL MUST 
# be enabled in the BIOS/UEFI settings (Power Management section). 
# Without the BIOS setting, the hardware will not power the NIC while off.
#
# NOTE: It is RECOMMENDED to run this script at every boot (e.g., via crontab).
# This ensures that any changes to the local IP address are updated in Fluxio.
# ==============================================================================

URL="https://vbpmfulxbpcuboirjokv.supabase.co/rest/v1/devices"
API_KEY="sb_publishable_RtG4GlKSSxRk3fCLYfXwjA_a-d50Yvp"

echo "--- Fluxio System Setup (Linux) ---"

# 1. Enable SSH
echo "[1/2] Enabling SSH service..."
sudo systemctl enable --now ssh 2>/dev/null || sudo service ssh enable

# 2. Sync Data to Supabase
echo "[2/2] Synchronizing with Fluxio Cloud..."
IP=$(hostname -I | awk '{print $1}')
INTERFACE=$(ip route show default | awk '/default/ {print $5}')
MAC=$(cat /sys/class/net/$INTERFACE/address)
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

curl -s -X PATCH "$URL?ip_address=eq.$IP" \
-H "apikey: $API_KEY" \
-H "Authorization: Bearer $API_KEY" \
-H "Content-Type: application/json" \
-d "{
  \"mac_address\": \"$MAC\",
  \"status\": \"Online\",
  \"last_seen\": \"$TIMESTAMP\"
}"

echo -e "\nDone. Device synced via IP: $IP"