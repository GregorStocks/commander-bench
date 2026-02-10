"""Tests for port availability checking."""

import socket
from unittest.mock import patch

import pytest

from puppeteer.port import can_bind_port, find_available_port, is_port_in_use, wait_for_port


def test_is_port_in_use_open():
    """A port with a listening socket should be detected as in use."""
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", 0))
    server.listen(1)
    port = server.getsockname()[1]
    try:
        assert is_port_in_use("127.0.0.1", port) is True
    finally:
        server.close()


def test_is_port_in_use_closed():
    """A port with no listener should not be detected as in use."""
    # Bind to get a free port, then close immediately
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.bind(("127.0.0.1", 0))
    port = server.getsockname()[1]
    server.close()
    assert is_port_in_use("127.0.0.1", port) is False


def test_can_bind_port_free():
    """Should return True for a port we can bind to."""
    # Find a free port by binding to 0
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.bind(("", 0))
    port = sock.getsockname()[1]
    sock.close()
    assert can_bind_port(port) is True


def test_can_bind_port_occupied():
    """Should return False for a port already bound."""
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("", 0))
    port = server.getsockname()[1]
    try:
        # Port is bound but not with SO_REUSEPORT, so a second bind should fail
        assert can_bind_port(port) is False
    finally:
        server.close()


def test_find_available_port():
    """Should find an available port in a range."""
    port = find_available_port("localhost", 19000, max_attempts=100)
    assert port >= 19000
    assert port < 19100


def test_find_available_port_exhausted():
    """Should raise RuntimeError when no ports are available."""
    with patch("puppeteer.port.can_bind_port", return_value=False), \
            pytest.raises(RuntimeError, match="No available port found"):
        find_available_port("localhost", 19000, max_attempts=5)


def test_wait_for_port_immediate():
    """Should return True immediately when port is already listening."""
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", 0))
    server.listen(1)
    port = server.getsockname()[1]
    try:
        assert wait_for_port("127.0.0.1", port, timeout=2) is True
    finally:
        server.close()


def test_wait_for_port_timeout():
    """Should return False after timeout when port never opens."""
    with patch("puppeteer.port.is_port_in_use", return_value=False), \
            patch("puppeteer.port.time") as mock_time:
            # Simulate time passing: first call returns 0, second returns timeout+1
            mock_time.time.side_effect = [0, 0, 2]
            mock_time.sleep = lambda x: None
            assert wait_for_port("127.0.0.1", 19999, timeout=1) is False
