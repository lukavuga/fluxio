#!/bin/zsh
echo "[Fluxio] Configuring macOS..."
sudo systemsetup -setremotelogin on
sudo pmset -a womp 1
echo "[Fluxio] Setup complete."
