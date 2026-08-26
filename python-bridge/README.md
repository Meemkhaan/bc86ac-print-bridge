# BC-86AC Print Bridge (Python)

A cross-platform print bridge for 80mm thermal printers (BC-86AC and compatible ESC/POS printers).

This is a Python port of the Android [bc86ac-bridge-app](../bc86ac-bridge-app), providing the same functionality on Linux/Windows/macOS:
- HTTP API server (port 9876) matching the Android app's contract
- USB printing via `/dev/usb/lp*` (Linux) or Windows USB
- Network printing via TCP port 9100
- Supabase polling for cloud print queues
- Auto USB device detection
- Health/status endpoints
- Test print endpoints

## Architecture

```
┌─────────────┐     HTTP/JSON      ┌──────────────────┐
│  Web App    │ ─────────────────► │  Print Bridge    │
│  (Vercel,   │   POST /print      │  (this service)  │
│   Chrome)   │                    │                  │
└─────────────┘                    │  ┌────────────┐  │
                                   │  │ USB Detect │  │
                                   │  └────────────┘  │
                                   │  ┌────────────┐  │
         ┌──────────────┐         │  │ Supabase   │  │
         │  Thermal     │◄────────┤  │ Poller     │  │
         │  Printer     │  ESC/POS│  └────────────┘  │
         │  (USB/LAN)   │         │                  │
         └──────────────┘         └──────────────────┘
```

## Quick Start

### Local Development

```bash
# Clone and enter directory
cd bc86ac-print-bridge-python

# Create virtual environment
python3 -m venv .venv
source .venv/bin/activate  # Windows: .venv\Scripts\activate

# Install dependencies
pip install -r requirements.txt

# Copy and edit config
cp .env.example .env
# Edit .env with your printer IP, bridge secret, etc.

# Run
python print_bridge.py
```

### Linux Deployment (systemd)

```bash
# Run deployment script as root
sudo ./deploy.sh

# Edit configuration
sudo nano /opt/bc86ac-print-bridge-python/.env

# Start service
sudo systemctl start bc86ac-print-bridge

# Check status
sudo systemctl status bc86ac-print-bridge

# Follow logs
sudo journalctl -u bc86ac-print-bridge -f
```

## Configuration (`.env`)

| Variable | Default | Description |
|----------|---------|-------------|
| `HOST` | `0.0.0.0` | HTTP server bind address |
| `PORT` | `9876` | HTTP server port |
| `PRINTER_IP` | `192.168.18.100` | Network printer IP |
| `PRINTER_PORT` | `9100` | Network printer port (RAW/TCP) |
| `USB_ENABLED` | `true` | Enable USB printer detection |
| `USB_DETECT_IVL` | `2` | USB poll interval (seconds) |
| `USB_DEV_GLOB` | `/dev/usb/lp*` | Linux USB device pattern |
| `TIMEOUT` | `5` | Print connection timeout (seconds) |
| `PROBE_TIMEOUT` | `1.5` | Printer probe timeout (seconds) |
| `NET_RETRIES` | `3` | Network print retry attempts |
| `NET_RETRY_MS` | `500` | Base retry delay (ms) |
| `SB_URL` | (empty) | Supabase project URL |
| `SB_KEY` | (empty) | Supabase anon key |
| `SB_POLL_IVL` | `2000` | Poll interval (ms) |
| `SB_MAX_JOBS` | `5` | Max jobs per poll |
| `SECRET` | (empty) | **Required** - API auth secret (64 hex chars) |

### Generate a Secure Secret

```bash
openssl rand -hex 32
```

## API Endpoints

All endpoints except `/health` require the `X-Bridge-Secret` header.

### Health Check (no auth)
```
GET /health
```
```json
{
  "ok": true,
  "name": "bc86ac-print-bridge-python",
  "usb_paired": true
}
```

### Status
```
GET /status
X-Bridge-Secret: your-secret
```
Returns detailed status including printer reachability, USB connection, job counts, Supabase poll status.

### Print Raw ESC/POS
```
POST /print
X-Bridge-Secret: your-secret
Content-Type: application/json

{
  "payload_base64": "BASE64_ENCODED_ESCPOS_BYTES",
  "transport": "auto",        // optional: "usb" | "network" | "auto"
  "printer_host": "192.168.1.50",  // optional override
  "printer_port": 9100        // optional override
}
```
Response:
```json
{ "success": true, "path": "usb" }
```

### Test Print
```
POST /test-print
X-Bridge-Secret: your-secret
Content-Type: application/json

{
  "transport": "auto",        // optional
  "printer_host": "192.168.1.50",  // optional
  "printer_port": 9100        // optional
}
```

### Test Supabase Connection
```
GET /supabase/test
X-Bridge-Secret: your-secret
```

## Supabase Integration

Create a `print_jobs` table in Supabase:

```sql
CREATE TABLE print_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payload_base64 TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'printing', 'done', 'error')),
    error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    printed_at TIMESTAMPTZ
);

-- Enable RLS
ALTER TABLE print_jobs ENABLE ROW LEVEL SECURITY;

-- Allow anon key to read pending and update status
CREATE POLICY "allow_pending_read" ON print_jobs
    FOR SELECT USING (status = 'pending');

CREATE POLICY "allow_status_update" ON print_jobs
    FOR UPDATE USING (true);
```

Enqueue a job from your web app:
```javascript
const { data, error } = await supabase
  .from('print_jobs')
  .insert({ payload_base64: btoa(escposBytes) });
```

The bridge will poll, print, and update status to `done` or `error`.

## USB Printing on Linux

The bridge uses the kernel's `usblp` driver which exposes USB printer class devices as `/dev/usb/lp0`, `/dev/usb/lp1`, etc.

### Verify USB Device
```bash
ls -la /dev/usb/lp*
lsusb | grep -i printer
```

### Permissions
The service runs as `www-data` user. Ensure it has access:
```bash
sudo usermod -a -G lp,dialout www-data
# Reboot or restart service
```

### udev Rule (optional, for persistent naming)
```bash
# /etc/udev/rules.d/99-bc86ac-printer.rules
SUBSYSTEM=="usb", ATTR{idVendor}=="0483", ATTR{idProduct}=="5740", MODE="0660", GROUP="lp", SYMLINK+="bc86ac-printer"
```
Then use `USB_DEVICE_GLOB=/dev/bc86ac-printer` in `.env`.

## Windows Notes

USB printing on Windows requires a different approach (WinUSB/libusb). For Windows:
1. Install the printer as a Windows printer
2. Use network printing (RAW port 9100) via print server
3. Or use a USB-to-Ethernet print server

Set `USB_ENABLED=false` in `.env` on Windows.

## Development

### Run Tests
```bash
# Health check
curl http://localhost:9876/health

# Status (with secret)
curl -H "X-Bridge-Secret: your-secret" http://localhost:9876/status

# Test print
curl -X POST -H "X-Bridge-Secret: your-secret" -H "Content-Type: application/json" \
  -d '{"transport":"network"}' http://localhost:9876/test-print

# Print raw ESC/POS (example: "Hello World" + cut)
curl -X POST -H "X-Bridge-Secret: your-secret" -H "Content-Type: application/json" \
  -d '{"payload_base64":"IBBQSGVsbG8gV29ybGQNCiAgIA0KDQ0KNVg="}' \
  http://localhost:9876/print
```

### Build ESC/POS Payloads
Use the `ReceiptBuilder` class in `print_bridge.py` or any ESC/POS library.

## API Compatibility with Android App

This Python bridge maintains the same HTTP API contract as the Android app:
- `GET /health` → `{ok, name, usb_paired}`
- `POST /print` → accepts `payload_base64`, `transport`, `printer_host`, `printer_port`
- `GET /status` → detailed status object
- CORS enabled for all origins

## License

MIT