#!/bin/bash
# Deployment script for BC-86AC Print Bridge (Python)
# Run as root on target Linux machine (e.g., Raspberry Pi, Ubuntu server)

set -e

APP_DIR="/opt/bc86ac-print-bridge-python"
SERVICE_NAME="bc86ac-print-bridge"
PYTHON_VERSION="3.11"

echo "=== BC-86AC Print Bridge Deployment ==="
echo "Target directory: $APP_DIR"

# Check if running as root
if [[ $EUID -ne 0 ]]; then
   echo "This script must be run as root (use sudo)"
   exit 1
fi

# Install system dependencies
echo "Installing system dependencies..."
apt-get update
apt-get install -y python3 python3-venv python3-pip usbutils

# Create app directory
echo "Creating app directory..."
mkdir -p "$APP_DIR"

# Copy application files (assuming you're running from the project root)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cp "$SCRIPT_DIR/print_bridge.py" "$APP_DIR/"
cp "$SCRIPT_DIR/requirements.txt" "$APP_DIR/"
cp "$SCRIPT_DIR/.env.example" "$APP_DIR/.env.example"
cp "$SCRIPT_DIR/bc86ac-print-bridge.service" "/etc/systemd/system/$SERVICE_NAME.service"

# Create virtual environment
echo "Creating virtual environment..."
python3 -m venv "$APP_DIR/.venv"
"$APP_DIR/.venv/bin/pip" install --upgrade pip
"$APP_DIR/.venv/bin/pip" install -r "$APP_DIR/requirements.txt"

# Set up config
if [[ ! -f "$APP_DIR/.env" ]]; then
    echo "Creating .env from example..."
    cp "$APP_DIR/.env.example" "$APP_DIR/.env"
    echo "IMPORTANT: Edit $APP_DIR/.env with your settings!"
fi

# Set permissions
chown -R www-data:www-data "$APP_DIR"
chmod 640 "$APP_DIR/.env"

# Add www-data to lp and dialout groups for USB access
usermod -a -G lp,dialout www-data

# Reload systemd and enable service
echo "Configuring systemd service..."
systemctl daemon-reload
systemctl enable "$SERVICE_NAME"

echo ""
echo "=== Deployment Complete ==="
echo ""
echo "Next steps:"
echo "1. Edit configuration: nano $APP_DIR/.env"
echo "2. Set PRINTER_IP, BRIDGE_SECRET, and optionally SUPABASE_URL/KEY"
echo "3. Start service: systemctl start $SERVICE_NAME"
echo "4. Check status: systemctl status $SERVICE_NAME"
echo "5. View logs: journalctl -u $SERVICE_NAME -f"
echo ""
echo "API will be available at: http://$(hostname -I | awk '{print $1}'):9876"
echo "Health check: curl http://localhost:9876/health"
echo "Status (with auth): curl -H \"X-Bridge-Secret: YOUR_SECRET\" http://localhost:9876/status"