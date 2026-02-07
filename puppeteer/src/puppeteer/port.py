"""Port availability checking."""

import socket
import time


def is_port_in_use(host: str, port: int, timeout: float = 1.0) -> bool:
    """Check if a port is in use by attempting to connect (something is listening)."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(timeout)
    try:
        result = sock.connect_ex((host, port))
        return result == 0  # Zero means connection succeeded = port in use
    finally:
        sock.close()


def can_bind_port(port: int) -> bool:
    """Check if we can actually bind to a port. More reliable than connect-based
    checks since it detects TIME_WAIT and other states that prevent binding."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        sock.bind(("", port))
        return True
    except OSError:
        return False
    finally:
        sock.close()


def find_available_port(host: str, start_port: int, max_attempts: int = 100) -> int:
    """Find an available port starting from start_port.
    Uses bind() to check availability, which catches TIME_WAIT and other
    states that connect-based checks miss. Also checks secondary port (port+8)."""
    for offset in range(max_attempts):
        port = start_port + offset
        if can_bind_port(port) and can_bind_port(port + 8):
            return port
    raise RuntimeError(
        f"No available port found in range {start_port}-{start_port + max_attempts}"
    )


def wait_for_port(host: str, port: int, timeout: int, poll_interval: float = 1.0) -> bool:
    """Wait for a port to become reachable (server started)."""
    start = time.time()
    while time.time() - start < timeout:
        if is_port_in_use(host, port):
            return True
        time.sleep(poll_interval)
    return False
