#!/usr/bin/env python3
"""
BC-86AC Print Bridge (Python)

A cross-platform print bridge that mirrors the Android app's logic:
- HTTP server on port 9876 (same API contract as Android app)
- USB printing via /dev/usb/lp* (Linux) or Windows USB printing
- Network printing via TCP port 9100
- Supabase polling for cloud print jobs
- Auto USB device detection
- Health/status endpoints
- Test print endpoints
"""

import base64
import glob
import hmac
import json
import logging
import os
import signal
import socket
import ssl
import threading
import time
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional, List, Tuple

import httpx
from dotenv import load_dotenv
from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse
from pydantic import BaseModel

# ============================================================
# Configuration (all required via .env)
# ============================================================

load_dotenv()

def _req(name: str) -> str:
    val = os.getenv(name)
    if not val:
        raise RuntimeError(f"Missing required env var: {name}")
    return val

def _req_int(name: str) -> int:
    return int(_req(name))

def _req_float(name: str) -> float:
    return float(_req(name))

def _req_bool(name: str) -> bool:
    return _req(name).lower() == "true"

# Server
PORT = _req_int("PORT")
HOST = _req("HOST")

# Printer (Network)
PRINTER_IP = _req("PRINTER_IP")
PRINTER_PORT = _req_int("PRINTER_PORT")

# Printer (USB)
USB_ENABLED = _req_bool("USB_ENABLED")
USB_DETECT_INTERVAL = _req_float("USB_DETECT_IVL")
USB_DEVICE_GLOB = _req("USB_DEV_GLOB")

# Timeouts
PRINTER_TIMEOUT = _req_float("TIMEOUT")
PRINTER_PROBE_TIMEOUT = _req_float("PROBE_TIMEOUT")
NETWORK_RETRIES = _req_int("NET_RETRIES")
NETWORK_RETRY_DELAY_MS = _req_int("NET_RETRY_MS")

# Supabase (optional - only if SB_URL and SB_KEY provided)
SUPABASE_URL = os.getenv("SB_URL", "").rstrip("/")
SUPABASE_ANON_KEY = os.getenv("SB_KEY", "")
SUPABASE_POLL_INTERVAL_MS = int(os.getenv("SB_POLL_IVL", "2000"))
SUPABASE_MAX_JOBS_PER_POLL = int(os.getenv("SB_MAX_JOBS", "5"))
SUPABASE_ENABLED = bool(SUPABASE_URL and SUPABASE_ANON_KEY)

# Auth
BRIDGE_SECRET = _req("SECRET")

# ============================================================
# Settings Management (persisted to settings.json)
# ============================================================

SETTINGS_FILE = Path("settings.json")

DEFAULT_SETTINGS = {
    "host": HOST,
    "port": PORT,
    "printer_ip": PRINTER_IP,
    "printer_port": PRINTER_PORT,
    "usb_enabled": USB_ENABLED,
    "usb_detect_ivl": USB_DETECT_INTERVAL,
    "usb_dev_glob": USB_DEVICE_GLOB,
    "timeout": PRINTER_TIMEOUT,
    "probe_timeout": PRINTER_PROBE_TIMEOUT,
    "net_retries": NETWORK_RETRIES,
    "net_retry_ms": NETWORK_RETRY_DELAY_MS,
    "sb_url": SUPABASE_URL,
    "sb_key": SUPABASE_ANON_KEY,
    "sb_poll_ivl": SUPABASE_POLL_INTERVAL_MS,
    "sb_max_jobs": SUPABASE_MAX_JOBS_PER_POLL,
    "secret": BRIDGE_SECRET,
}

_settings_lock = threading.Lock()
_current_settings = DEFAULT_SETTINGS.copy()

def load_settings() -> dict:
    global _current_settings
    with _settings_lock:
        if SETTINGS_FILE.exists():
            try:
                with open(SETTINGS_FILE) as f:
                    saved = json.load(f)
                _current_settings.update(saved)
            except Exception as e:
                logging.warning(f"Failed to load settings.json: {e}")
        return _current_settings.copy()

def save_settings(new_settings: dict) -> dict:
    global _current_settings
    with _settings_lock:
        # Merge with current, preserving unknown keys
        _current_settings.update(new_settings)
        try:
            with open(SETTINGS_FILE, "w") as f:
                json.dump(_current_settings, f, indent=2)
        except Exception as e:
            logging.error(f"Failed to save settings.json: {e}")
            raise
        return _current_settings.copy()

def get_settings() -> dict:
    with _settings_lock:
        return _current_settings.copy()

def update_runtime_settings(new_settings: dict) -> dict:
    """Apply settings that can change at runtime (no restart needed)."""
    global PRINTER_IP, PRINTER_PORT, USB_ENABLED, USB_DETECT_INTERVAL
    global USB_DEVICE_GLOB, PRINTER_TIMEOUT, PRINTER_PROBE_TIMEOUT
    global NETWORK_RETRIES, NETWORK_RETRY_DELAY_MS
    global SUPABASE_URL, SUPABASE_ANON_KEY, SUPABASE_POLL_INTERVAL_MS, SUPABASE_MAX_JOBS_PER_POLL
    global BRIDGE_SECRET
    
    changed = {}
    
    # Runtime-configurable
    if "printer_ip" in new_settings:
        PRINTER_IP = new_settings["printer_ip"]
        changed["printer_ip"] = PRINTER_IP
    if "printer_port" in new_settings:
        PRINTER_PORT = new_settings["printer_port"]
        changed["printer_port"] = PRINTER_PORT
    if "usb_enabled" in new_settings:
        USB_ENABLED = new_settings["usb_enabled"]
        changed["usb_enabled"] = USB_ENABLED
    if "usb_detect_ivl" in new_settings:
        USB_DETECT_INTERVAL = new_settings["usb_detect_ivl"]
        changed["usb_detect_ivl"] = USB_DETECT_INTERVAL
    if "usb_dev_glob" in new_settings:
        USB_DEVICE_GLOB = new_settings["usb_dev_glob"]
        changed["usb_dev_glob"] = USB_DEVICE_GLOB
    if "timeout" in new_settings:
        PRINTER_TIMEOUT = new_settings["timeout"]
        changed["timeout"] = PRINTER_TIMEOUT
    if "probe_timeout" in new_settings:
        PRINTER_PROBE_TIMEOUT = new_settings["probe_timeout"]
        changed["probe_timeout"] = PRINTER_PROBE_TIMEOUT
    if "net_retries" in new_settings:
        NETWORK_RETRIES = new_settings["net_retries"]
        changed["net_retries"] = NETWORK_RETRIES
    if "net_retry_ms" in new_settings:
        NETWORK_RETRY_DELAY_MS = new_settings["net_retry_ms"]
        changed["net_retry_ms"] = NETWORK_RETRY_DELAY_MS
    if "sb_url" in new_settings:
        SUPABASE_URL = new_settings["sb_url"].rstrip("/")
        changed["sb_url"] = SUPABASE_URL
    if "sb_key" in new_settings:
        SUPABASE_ANON_KEY = new_settings["sb_key"]
        changed["sb_key"] = SUPABASE_ANON_KEY
    if "sb_poll_ivl" in new_settings:
        SUPABASE_POLL_INTERVAL_MS = new_settings["sb_poll_ivl"]
        changed["sb_poll_ivl"] = SUPABASE_POLL_INTERVAL_MS
    if "sb_max_jobs" in new_settings:
        SUPABASE_MAX_JOBS_PER_POLL = new_settings["sb_max_jobs"]
        changed["sb_max_jobs"] = SUPABASE_MAX_JOBS_PER_POLL
    if "secret" in new_settings:
        BRIDGE_SECRET = new_settings["secret"]
        changed["secret"] = "***"
    
    # Require restart
    restart_needed = []
    if "host" in new_settings and new_settings["host"] != HOST:
        restart_needed.append("host")
    if "port" in new_settings and new_settings["port"] != PORT:
        restart_needed.append("port")
    
    return {"applied": changed, "restart_needed": restart_needed}

# Load persisted settings on startup
load_settings()

# ============================================================
# Logging
# ============================================================

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(levelname)s | %(message)s",
)
logger = logging.getLogger("bc86ac-print-bridge")

# ============================================================
# Runtime State
# ============================================================

worker_running = True
started_at = datetime.now(timezone.utc)

last_error: Optional[str] = None
last_print_at: int = 0
last_poll_at: int = 0
last_poll_detail: Optional[str] = None
last_printed_job_id: Optional[str] = None
last_jobs_found: int = 0
jobs_processed: int = 0
jobs_failed: int = 0

usb_device_path: Optional[str] = None
active_print_path: str = "none"
supabase_last_error: Optional[str] = None

state_lock = threading.Lock()

# Threads
_usb_thread: Optional[threading.Thread] = None
_supabase_thread: Optional[threading.Thread] = None

# ============================================================
# Graceful Shutdown
# ============================================================

def _shutdown_handler(signum, frame):
    global worker_running
    logger.info("Received signal %d — shutting down.", signum)
    worker_running = False

signal.signal(signal.SIGTERM, _shutdown_handler)
signal.signal(signal.SIGINT, _shutdown_handler)

# ============================================================
# Request Models
# ============================================================

class PrintRequest(BaseModel):
    """Raw ESC/POS bytes (base64) with optional transport override."""
    payload_base64: str
    transport: Optional[str] = None  # "usb" | "network" | None (auto)
    printer_host: Optional[str] = None
    printer_port: Optional[int] = None

class TestPrintRequest(BaseModel):
    """Trigger a test print page."""
    transport: Optional[str] = None
    printer_host: Optional[str] = None
    printer_port: Optional[int] = None

# ============================================================
# Auth
# ============================================================

def require_secret(x_bridge_secret: str = Header(default="")):
    if not BRIDGE_SECRET or not hmac.compare_digest(x_bridge_secret, BRIDGE_SECRET):
        raise HTTPException(
            status_code=401,
            detail="Invalid or missing X-Bridge-Secret",
        )

# ============================================================
# ESC/POS Receipt Builder
# ============================================================

class ReceiptBuilder:
    """Minimal ESC/POS command builder (matches Android's EscPos.kt)."""
    
    def __init__(self):
        self.buf = bytearray()
    
    def init(self) -> "ReceiptBuilder":
        self.buf.extend(b"\x1b\x40")
        return self
    
    def align(self, pos: str) -> "ReceiptBuilder":
        n = {"center": 1, "right": 2}.get(pos, 0)
        self.buf.extend(bytes([0x1b, 0x61, n]))
        return self
    
    def bold(self, on: bool) -> "ReceiptBuilder":
        self.buf.extend(bytes([0x1b, 0x45, 1 if on else 0]))
        return self
    
    def double_size(self, on: bool) -> "ReceiptBuilder":
        self.buf.extend(bytes([0x1d, 0x21, 0x11 if on else 0x00]))
        return self
    
    def text(self, s: str) -> "ReceiptBuilder":
        self.buf.extend(s.encode("utf-8"))
        return self
    
    def line(self, s: str = "") -> "ReceiptBuilder":
        self.text(s)
        self.buf.append(0x0a)
        return self
    
    def feed(self, n: int = 1) -> "ReceiptBuilder":
        self.buf.extend(b"\x0a" * n)
        return self
    
    def divider(self, width: int = 32) -> "ReceiptBuilder":
        return self.line("-" * width)
    
    def cut(self) -> "ReceiptBuilder":
        self.buf.extend(b"\x1d\x56\x01")
        return self
    
    def build(self) -> bytes:
        return bytes(self.buf)

def build_test_page() -> bytes:
    """Build a test page matching Android's buildTestPage()."""
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    return (ReceiptBuilder()
        .init()
        .align("center")
        .double_size(True)
        .line("TEST PRINT")
        .double_size(False)
        .line("BC-86AC Print Bridge (Python)")
        .divider()
        .align("left")
        .line("USB / Network connection OK")
        .line(f"Time: {now}")
        .divider()
        .align("center")
        .bold(True)
        .line("Bridge is working")
        .bold(False)
        .feed(3)
        .cut()
        .build())

# ============================================================
# Printer — LAN/TCP Path
# ============================================================

def send_to_printer_network(host: str, port: int, data: bytes) -> None:
    """Send raw ESC/POS bytes to the printer over TCP."""
    if not data:
        raise ValueError("Print payload is empty.")
    
    logger.info("Sending %d bytes to printer %s:%d", len(data), host, port)
    
    with socket.create_connection((host, port), timeout=PRINTER_TIMEOUT) as printer:
        printer.sendall(data)
        try:
            printer.shutdown(socket.SHUT_WR)
        except Exception:
            pass
        time.sleep(0.2)
    
    logger.info("Printer accepted %d bytes.", len(data))

def send_to_printer_network_with_retry(host: str, port: int, data: bytes) -> None:
    """Network print with retry logic (matches Android's printOverNetworkWithRetry)."""
    last_exception = None
    for attempt in range(1, NETWORK_RETRIES + 1):
        try:
            logger.debug("Network print attempt %d/%d to %s:%d", attempt, NETWORK_RETRIES, host, port)
            send_to_printer_network(host, port, data)
            return
        except Exception as e:
            last_exception = e
            logger.warning("Attempt %d failed: %s", attempt, e)
            if attempt < NETWORK_RETRIES:
                delay = attempt * NETWORK_RETRY_DELAY_MS / 1000
                logger.debug("Retrying in %.2fs...", delay)
                time.sleep(delay)
    raise last_exception

def check_printer_reachable(host: str = None, port: int = None, timeout: float = PRINTER_PROBE_TIMEOUT) -> bool:
    """Check if printer is reachable via TCP."""
    host = host or PRINTER_IP
    port = port or PRINTER_PORT
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False

# ============================================================
# Printer — USB Path
# ============================================================

def find_usb_printer() -> Optional[str]:
    """Find USB printer device (Linux /dev/usb/lp*)."""
    nodes = sorted(glob.glob(USB_DEVICE_GLOB))
    return nodes[0] if nodes else None

def usb_detect_loop() -> None:
    """Poll for USB attach/detach events."""
    global usb_device_path
    
    logger.info("USB detect loop started (interval %.1fs, glob: %s).", USB_DETECT_INTERVAL, USB_DEVICE_GLOB)
    
    while worker_running:
        found = find_usb_printer()
        
        with state_lock:
            if found != usb_device_path:
                logger.info(
                    "USB printer %s: %s",
                    "attached" if found else "detached",
                    found or usb_device_path,
                )
                usb_device_path = found
        
        time.sleep(USB_DETECT_INTERVAL)

def send_to_printer_usb(data: bytes, device_path: str) -> None:
    """Direct write to USB printer character device."""
    with open(device_path, "wb") as f:
        f.write(data)
        f.flush()

def send_to_printer_auto(data: bytes, transport: Optional[str] = None, 
                         host: Optional[str] = None, port: Optional[int] = None) -> str:
    """
    Auto-select print path: USB first if available, LAN otherwise.
    Returns which path succeeded: "usb" or "lan".
    """
    global active_print_path
    
    host = host or PRINTER_IP
    port = port or PRINTER_PORT
    
    with state_lock:
        device_path = usb_device_path
    
    # Explicit transport override
    if transport == "usb":
        if not USB_ENABLED or not device_path:
            raise RuntimeError("USB printing requested but no USB printer available")
        send_to_printer_usb(data, device_path)
        with state_lock:
            active_print_path = "usb"
        return "usb"
    
    if transport == "network":
        send_to_printer_network_with_retry(host, port, data)
        with state_lock:
            active_print_path = "lan"
        return "lan"
    
    # Auto mode: USB first, fallback to network
    if USB_ENABLED and device_path:
        try:
            send_to_printer_usb(data, device_path)
            with state_lock:
                active_print_path = "usb"
            return "usb"
        except Exception as exc:
            logger.warning("USB print failed (%s) — falling back to LAN.", exc)
    
    send_to_printer_network_with_retry(host, port, data)
    with state_lock:
        active_print_path = "lan"
    return "lan"

# ============================================================
# Supabase Poller
# ============================================================

async def poll_supabase() -> None:
    """Poll Supabase for pending print jobs (matches Android's SupabasePoller)."""
    global last_poll_at, last_poll_detail, last_printed_job_id
    global last_jobs_found, supabase_last_error, jobs_processed, jobs_failed
    
    if not SUPABASE_URL or not SUPABASE_ANON_KEY:
        with state_lock:
            last_poll_detail = "No Supabase config set"
        return
    
    base_url = SUPABASE_URL
    api_key = SUPABASE_ANON_KEY
    
    try:
        # Fetch pending jobs
        async with httpx.AsyncClient(timeout=8.0) as client:
            resp = await client.get(
                f"{base_url}/rest/v1/print_jobs",
                params={
                    "select": "id,payload_base64",
                    "status": "eq.pending",
                    "order": "created_at.asc",
                    "limit": SUPABASE_MAX_JOBS_PER_POLL,
                },
                headers={
                    "apikey": api_key,
                    "Authorization": f"Bearer {api_key}",
                },
            )
            
            if resp.status_code not in (200, 201, 204):
                raise RuntimeError(f"Supabase GET returned {resp.status_code}: {resp.text}")
            
            jobs = resp.json()
            last_jobs_found = len(jobs)
            
            if not jobs:
                last_poll_detail = "GET ok, 0 pending jobs"
                return
            
            details = []
            for job in jobs:
                job_id = job["id"]
                payload_b64 = job["payload_base64"]
                
                try:
                    # Mark as printing
                    await patch_job_status(base_url, api_key, job_id, "printing", None)
                    
                    # Decode and print
                    print_bytes = base64.b64decode(payload_b64, validate=True)
                    if not print_bytes:
                        raise ValueError("Decoded to zero bytes")
                    
                    logger.info("Job %s: payload_base64 length=%d, decoded %d bytes", 
                               job_id, len(payload_b64), len(print_bytes))
                    
                    send_to_printer_auto(print_bytes)
                    
                    # Mark as done
                    await patch_job_status(base_url, api_key, job_id, "done", None)
                    last_printed_job_id = job_id
                    details.append(f"{job_id} -> done")
                    
                    with state_lock:
                        jobs_processed += 1
                        last_error = None
                        
                except Exception as e:
                    logger.warning("Job %s failed: %s", job_id, e)
                    supabase_last_error = f"Job {job_id}: {e}"
                    details.append(f"{job_id} -> error: {e}")
                    
                    with state_lock:
                        jobs_failed += 1
                    
                    try:
                        await patch_job_status(base_url, api_key, job_id, "error", str(e))
                    except Exception as e2:
                        logger.warning("Could not record error for %s: %s", job_id, e2)
                        details.append(f"{job_id} -> error-recording also failed: {e2}")
            
            last_poll_detail = "; ".join(details)
            
    except Exception as e:
        logger.warning("Poll error: %s", e)
        supabase_last_error = f"Poll failed: {e}"
        last_poll_detail = f"Exception: {e}"
    
    last_poll_at = int(time.time() * 1000)

async def patch_job_status(base_url: str, api_key: str, job_id: str, status: str, error: Optional[str]) -> None:
    """Update job status in Supabase (PATCH via raw HTTPS like Android)."""
    body = {"status": status}
    if error:
        body["error"] = error
    if status == "done":
        body["printed_at"] = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    
    body_bytes = json.dumps(body).encode("utf-8")
    
    # Parse URL for raw socket
    from urllib.parse import urlparse
    parsed = urlparse(base_url)
    host = parsed.hostname
    port = parsed.port or 443
    path = f"{parsed.path}/rest/v1/print_jobs?id=eq.{job_id}"
    
    # Use httpx for simplicity (handles SSL properly)
    async with httpx.AsyncClient(timeout=8.0) as client:
        resp = await client.patch(
            f"{base_url}/rest/v1/print_jobs?id=eq.{job_id}",
            content=body_bytes,
            headers={
                "apikey": api_key,
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json",
                "Prefer": "return=minimal",
            },
        )
        if resp.status_code not in (200, 201, 204):
            raise RuntimeError(f"Supabase PATCH returned {resp.status_code}")

def supabase_poll_loop() -> None:
    """Background thread for Supabase polling."""
    logger.info("Supabase poll loop started (interval %dms).", SUPABASE_POLL_INTERVAL_MS)
    
    while worker_running:
        try:
            import asyncio
            asyncio.run(poll_supabase())
        except Exception as e:
            logger.warning("Supabase poll loop error: %s", e)
        
        time.sleep(SUPABASE_POLL_INTERVAL_MS / 1000)

# ============================================================
# FastAPI Lifespan
# ============================================================

@asynccontextmanager
async def lifespan(app: FastAPI):
    global worker_running, _usb_thread, _supabase_thread, started_at
    
    worker_running = True
    started_at = datetime.now(timezone.utc)
    
    # Start USB detection thread
    if USB_ENABLED:
        _usb_thread = threading.Thread(
            target=usb_detect_loop,
            name="usb-detect",
            daemon=True,
        )
        _usb_thread.start()
        logger.info("Print bridge ready. USB detect thread started.")
    else:
        logger.info("Print bridge ready. USB disabled.")
    
    # Start Supabase poller if configured
    if SUPABASE_URL and SUPABASE_ANON_KEY:
        _supabase_thread = threading.Thread(
            target=supabase_poll_loop,
            name="supabase-poll",
            daemon=True,
        )
        _supabase_thread.start()
        logger.info("Supabase poller started.")
    else:
        logger.info("Supabase not configured; poller not started.")
    
    yield
    
    worker_running = False
    
    if _usb_thread:
        _usb_thread.join(timeout=5)
    if _supabase_thread:
        _supabase_thread.join(timeout=5)
    
    logger.info("Print bridge stopped.")

# ============================================================
# FastAPI App
# ============================================================

app = FastAPI(
    title="BC-86AC Print Bridge (Python)",
    version="1.0.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ============================================================
# API Endpoints (matching Android app's API contract)
# ============================================================

@app.get("/health")
def health():
    """Liveness probe — never touches the printer."""
    return {
        "ok": True,
        "name": "bc86ac-print-bridge-python",
        "usb_paired": usb_device_path is not None,
    }

@app.get("/status", dependencies=[Depends(require_secret)])
def status():
    """Detailed status endpoint."""
    with state_lock:
        error = last_error
        printed = last_printed_job_id
        via = active_print_path
        processed = jobs_processed
        failed = jobs_failed
        usb_path = usb_device_path
        active_path = active_print_path
        supabase_err = supabase_last_error
        poll_detail = last_poll_detail
        jobs_found = last_jobs_found
    
    uptime_s = (datetime.now(timezone.utc) - started_at).total_seconds()
    
    return {
        "status": "online",
        "version": "1.0.0",
        "uptime_seconds": round(uptime_s),
        "printer_ip": PRINTER_IP,
        "printer_port": PRINTER_PORT,
        "printer_reachable": check_printer_reachable(),
        "usb_connected": usb_path is not None,
        "usb_device_path": usb_path,
        "active_print_path": active_path,
        "last_print_at": last_print_at,
        "last_printed_job_id": printed,
        "last_printed_via": via,
        "last_error": error,
        "jobs_processed": processed,
        "jobs_failed": failed,
        # Supabase
        "supabase_configured": bool(SUPABASE_URL and SUPABASE_ANON_KEY),
        "supabase_last_poll_at": last_poll_at,
        "supabase_last_error": supabase_err,
        "supabase_last_poll_detail": poll_detail,
        "supabase_last_jobs_found": jobs_found,
    }

@app.post("/print", dependencies=[Depends(require_secret)])
def print_raw(job: PrintRequest):
    """
    Primary print endpoint — matches Android app's /print.
    Accepts raw ESC/POS bytes (base64) with optional transport override.
    """
    global jobs_processed, jobs_failed, last_print_at, last_printed_job_id, last_error
    
    try:
        print_bytes = base64.b64decode(job.payload_base64, validate=True)
    except Exception as exc:
        raise HTTPException(
            status_code=400,
            detail=f"Invalid base64 payload: {exc}",
        )
    
    if not print_bytes:
        raise HTTPException(status_code=400, detail="Decoded to zero bytes")
    
    try:
        used = send_to_printer_auto(
            print_bytes,
            transport=job.transport,
            host=job.printer_host,
            port=job.printer_port,
        )
        last_print_at = int(time.time() * 1000)
        
        with state_lock:
            jobs_processed += 1
            last_error = None
            active_print_path = used
            last_printed_job_id = f"direct-{last_print_at}"
        
        logger.info("Direct print succeeded via %s (%d bytes)", used, len(print_bytes))
        return {"success": True, "path": used}
        
    except Exception as exc:
        detail = str(exc) or "Print failed."
        logger.exception("Direct print failed")
        with state_lock:
            last_error = detail
            jobs_failed += 1
        raise HTTPException(status_code=502, detail=detail)

@app.post("/test-print", dependencies=[Depends(require_secret)])
def test_print(req: TestPrintRequest):
    """Print a test page (matches Android's test buttons)."""
    global jobs_processed, jobs_failed, last_print_at, last_error
    
    try:
        test_bytes = build_test_page()
        used = send_to_printer_auto(
            test_bytes,
            transport=req.transport,
            host=req.printer_host,
            port=req.printer_port,
        )
        last_print_at = int(time.time() * 1000)
        
        with state_lock:
            jobs_processed += 1
            last_error = None
            active_print_path = used
        
        logger.info("Test print succeeded via %s", used)
        return {"success": True, "path": used, "message": "Test page sent to printer"}
        
    except Exception as exc:
        detail = str(exc) or "Test print failed."
        logger.exception("Test print failed")
        with state_lock:
            last_error = detail
            jobs_failed += 1
        raise HTTPException(status_code=502, detail=detail)

@app.get("/supabase/test", dependencies=[Depends(require_secret)])
async def test_supabase():
    """Test Supabase connection (matches Android's testCloudSync)."""
    if not SUPABASE_URL or not SUPABASE_ANON_KEY:
        raise HTTPException(status_code=400, detail="Supabase not configured")
    
    try:
        async with httpx.AsyncClient(timeout=8.0) as client:
            resp = await client.get(
                f"{SUPABASE_URL}/rest/v1/print_jobs",
                params={
                    "select": "id",
                    "status": "eq.pending",
                    "order": "created_at.asc",
                    "limit": 5,
                },
                headers={
                    "apikey": SUPABASE_ANON_KEY,
                    "Authorization": f"Bearer {SUPABASE_ANON_KEY}",
                },
            )
            
            if resp.status_code not in (200, 201, 204):
                return {"success": False, "error": f"Supabase returned {resp.status_code}: {resp.text}"}
            
            jobs = resp.json()
            count = len(jobs)
            ids = [j["id"] for j in jobs]
            
            msg = f"GET ok — {count} pending job(s)"
            if ids:
                msg += f": {', '.join(ids)}"
            
            return {"success": True, "message": msg, "jobs_found": count, "job_ids": ids}
            
    except Exception as e:
        logger.exception("Supabase test failed")
        return {"success": False, "error": str(e)}

# ============================================================
# Settings API
# ============================================================

class SettingsUpdate(BaseModel):
    host: Optional[str] = None
    port: Optional[int] = None
    printer_ip: Optional[str] = None
    printer_port: Optional[int] = None
    usb_enabled: Optional[bool] = None
    usb_detect_ivl: Optional[float] = None
    usb_dev_glob: Optional[str] = None
    timeout: Optional[float] = None
    probe_timeout: Optional[float] = None
    net_retries: Optional[int] = None
    net_retry_ms: Optional[int] = None
    sb_url: Optional[str] = None
    sb_key: Optional[str] = None
    sb_poll_ivl: Optional[int] = None
    sb_max_jobs: Optional[int] = None
    secret: Optional[str] = None

@app.get("/settings", dependencies=[Depends(require_secret)])
def get_settings_api():
    """Get current settings (with secret masked)."""
    s = get_settings()
    # Mask secret
    if "secret" in s:
        s = s.copy()
        s["secret"] = "***" if s["secret"] else ""
    return s

@app.post("/settings", dependencies=[Depends(require_secret)])
def update_settings_api(update: SettingsUpdate):
    """Update settings. Returns applied changes and restart_needed list."""
    new_settings = update.model_dump(exclude_unset=True)
    saved = save_settings(new_settings)
    result = update_runtime_settings(new_settings)
    result["saved"] = True
    return result

@app.get("/settings/ui", response_class=HTMLResponse)
def settings_ui():
    """Settings web UI."""
    return """
<!DOCTYPE html>
<html>
<head>
    <title>BC-86AC Print Bridge Settings</title>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <style>
        body { font-family: monospace; max-width: 600px; margin: 2rem auto; padding: 1rem; background: #1a1a1a; color: #eee; }
        h1 { color: #d98b1f; font-size: 1.2rem; letter-spacing: 0.08em; }
        .card { background: #242220; padding: 1rem; margin: 1rem 0; border-radius: 4px; }
        .row { display: flex; gap: 0.5rem; margin: 0.5rem 0; flex-wrap: wrap; }
        label { min-width: 140px; color: #8a8073; font-size: 0.85rem; }
        input, select { flex: 1; min-width: 200px; padding: 0.4rem; background: #1a1a1a; border: 1px solid #444; color: #eee; border-radius: 3px; }
        input[type="checkbox"] { width: auto; }
        .btn { padding: 0.5rem 1rem; background: #d98b1f; border: none; color: #1a1a1a; font-weight: bold; cursor: pointer; border-radius: 3px; }
        .btn:hover { background: #e8a030; }
        .restart-warning { color: #d98b1f; font-size: 0.8rem; margin-top: 0.5rem; }
        .status { padding: 0.5rem; margin: 1rem 0; border-radius: 3px; display: none; }
        .status.success { background: #1a3a1a; color: #4ade80; display: block; }
        .status.error { background: #3a1a1a; color: #f87171; display: block; }
    </style>
</head>
<body>
    <h1>BC-86AC Print Bridge Settings</h1>
    <div id="status" class="status"></div>
    
    <div class="card">
        <div class="row"><label>Host</label><input id="host" type="text"></div>
        <div class="row"><label>Port</label><input id="port" type="number"></div>
    </div>
    
    <div class="card">
        <div class="row"><label>Printer IP</label><input id="printer_ip" type="text"></div>
        <div class="row"><label>Printer Port</label><input id="printer_port" type="number"></div>
    </div>
    
    <div class="card">
        <div class="row"><label>USB Enabled</label><input id="usb_enabled" type="checkbox"></div>
        <div class="row"><label>USB Detect Interval (s)</label><input id="usb_detect_ivl" type="number" step="0.1"></div>
        <div class="row"><label>USB Device Glob</label><input id="usb_dev_glob" type="text"></div>
    </div>
    
    <div class="card">
        <div class="row"><label>Timeout (s)</label><input id="timeout" type="number" step="0.1"></div>
        <div class="row"><label>Probe Timeout (s)</label><input id="probe_timeout" type="number" step="0.1"></div>
        <div class="row"><label>Network Retries</label><input id="net_retries" type="number"></div>
        <div class="row"><label>Retry Delay (ms)</label><input id="net_retry_ms" type="number"></div>
    </div>
    
    <div class="card">
        <div class="row"><label>Supabase URL</label><input id="sb_url" type="text"></div>
        <div class="row"><label>Supabase Anon Key</label><input id="sb_key" type="password"></div>
        <div class="row"><label>Poll Interval (ms)</label><input id="sb_poll_ivl" type="number"></div>
        <div class="row"><label>Max Jobs/Poll</label><input id="sb_max_jobs" type="number"></div>
    </div>
    
    <div class="card">
        <div class="row"><label>Bridge Secret</label><input id="secret" type="password" placeholder="Leave empty to keep current"></div>
    </div>
    
    <button class="btn" onclick="saveSettings()">Save Settings</button>
    <div id="restart-warning" class="restart-warning" style="display:none;"></div>

    <script>
        const secretHeader = 'X-Bridge-Secret';
        let currentSecret = '';

        async function loadSettings() {
            try {
                const res = await fetch('/settings', { headers: { [secretHeader]: currentSecret } });
                if (!res.ok) throw new Error('Auth failed');
                const data = await res.json();
                Object.keys(data).forEach(key => {
                    const el = document.getElementById(key);
                    if (el) {
                        if (el.type === 'checkbox') el.checked = data[key];
                        else if (key !== 'secret') el.value = data[key];
                    }
                });
            } catch (e) {
                showStatus('Failed to load settings: ' + e.message, true);
            }
        }

        function showStatus(msg, isError = false) {
            const el = document.getElementById('status');
            el.textContent = msg;
            el.className = 'status ' + (isError ? 'error' : 'success');
        }

        async function saveSettings() {
            currentSecret = prompt('Enter Bridge Secret:') || '';
            if (!currentSecret) return;
            
            const data = {};
            document.querySelectorAll('input, select').forEach(el => {
                if (el.type === 'checkbox') data[el.id] = el.checked;
                else if (el.id === 'secret') {
                    if (el.value) data[el.id] = el.value;
                } else if (el.value !== '') {
                    data[el.id] = el.type === 'number' ? (el.step && el.step.includes('.') ? parseFloat(el.value) : parseInt(el.value)) : el.value;
                }
            });
            
            try {
                const res = await fetch('/settings', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json', [secretHeader]: currentSecret },
                    body: JSON.stringify(data)
                });
                const result = await res.json();
                if (!res.ok) throw new Error(result.detail || 'Save failed');
                
                showStatus('Settings saved' + (result.restart_needed.length ? ' — Restart needed for: ' + result.restart_needed.join(', ') : ''));
                document.getElementById('restart-warning').style.display = result.restart_needed.length ? 'block' : 'none';
                document.getElementById('restart-warning').textContent = result.restart_needed.length ? '⚠ Restart required for: ' + result.restart_needed.join(', ') : '';
                
                // Clear secret field
                document.getElementById('secret').value = '';
            } catch (e) {
                showStatus('Save failed: ' + e.message, true);
            }
        }

        // Load on page load
        currentSecret = prompt('Enter Bridge Secret to access settings:') || '';
        if (currentSecret) loadSettings();
    </script>
</body>
</html>
    """

# ============================================================
# Main Entry Point
# ============================================================

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host=HOST, port=PORT)