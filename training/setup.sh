#!/usr/bin/env bash
# Sets up the training environment on Kubuntu / Ubuntu with an NVIDIA card.
#
#   cd training && ./setup.sh
#
# Creates a virtual environment so nothing here touches your system Python.
set -euo pipefail

cd "$(dirname "$0")"

echo "==> System packages"
sudo apt-get update
sudo apt-get install -y python3-venv python3-pip ffmpeg libsndfile1

echo
echo "==> Checking for the GPU"
if command -v nvidia-smi >/dev/null 2>&1; then
    nvidia-smi --query-gpu=name,memory.total,driver_version --format=csv,noheader
else
    echo "nvidia-smi not found."
    echo "Install the driver first:  sudo ubuntu-drivers autoinstall  && reboot"
    echo "Continuing anyway — training will fall back to the CPU, slowly."
fi

echo
echo "==> Virtual environment"
python3 -m venv .venv
# shellcheck disable=SC1091
source .venv/bin/activate
pip install --upgrade pip wheel

echo
echo "==> PyTorch with CUDA"
# The CUDA build is a separate index; the default PyPI wheel is CPU-only and would train at a
# small fraction of the speed without ever saying why.
pip install torch --index-url https://download.pytorch.org/whl/cu124

echo
echo "==> Everything else"
pip install -r requirements.txt

echo
echo "==> Verifying"
python3 - <<'PY'
import torch
if torch.cuda.is_available():
    print(f"CUDA ready: {torch.cuda.get_device_name(0)} "
          f"({torch.cuda.get_device_properties(0).total_memory / 1e9:.0f} GB)")
else:
    print("WARNING: PyTorch cannot see the GPU. Training will use the CPU.")
    print("Usually this means the NVIDIA driver is missing or a reboot is pending.")
PY

echo
echo "Done. Activate it whenever you come back with:"
echo "    source training/.venv/bin/activate"
