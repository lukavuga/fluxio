#!/bin/zsh

# ==============================================================================
# FLUXIO DEPLOYMENT SCRIPT (macOS)
# ------------------------------------------------------------------------------
# IMPORTANT: This script enables 'Remote Login' (SSH). Ensure that 
# "Wake for network access" is enabled in System Settings > Energy Saver.
# Note: For Apple Silicon Macs, BIOS settings do not apply.
#
# NOTE: We STRONGLY RECOMMEND configuring this script as a Login Item. 
# This ensures the Fluxio App stays synchronized even if the IP changes.
# ==============================================================================

URL="https://vbpmfulxbpcuboirjokv.supabase.co/rest/v1/devices"
API_KEY="sb_publishable_RtG4GlKSSxRk3fCLYfXwjA_a-d50Yvp"

echo "--- Fluxio System Setup (macOS) ---"

# 1. Enable SSH (Remote Login)
echo "[1/2] Enabling Remote Login (SSH)..."
sudo systemsetup -setremotelogin on

# 2. Get Network Info
echo "[2/2] Synchronizing with Fluxio Cloud..."
IP=$(ipconfig getifaddr en0) || IP=$(ipconfig getifaddr en1)
MAC=$(networksetup -getmacaddress en0 2>/dev/null | awk '{print $3}')

if [ "$MAC" = "" ]; then
    MAC=$(ifconfig | grep -A 1 "en1" | grep ether | awk '{print $2}')
fi

TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

# 3. Update via IP matching
curl -s -L -X PATCH "$URL?ip_address=eq.$IP" \
-H "apikey: $API_KEY" \
-H "Authorization: Bearer $API_KEY" \
-H "Content-Type: application/json" \
-d "{
  \"mac_address\": \"$MAC\",
  \"status\": \"Online\",
  \"last_seen\": \"$TIMESTAMP\"
}"

echo "\nDone. Mac updated via IP: $IP"