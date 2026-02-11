"""Tests for ProcessManager signal handling."""

import signal
import threading

import pytest

from puppeteer.process_manager import ProcessManager


@pytest.fixture(autouse=True)
def _restore_signal_handlers():
    """Save and restore signal handlers so ProcessManager doesn't leak."""
    old_int = signal.getsignal(signal.SIGINT)
    old_term = signal.getsignal(signal.SIGTERM)
    old_hup = signal.getsignal(signal.SIGHUP) if hasattr(signal, "SIGHUP") else None
    yield
    signal.signal(signal.SIGINT, old_int)
    signal.signal(signal.SIGTERM, old_term)
    if old_hup is not None:
        signal.signal(signal.SIGHUP, old_hup)


def test_sigint_does_not_exit():
    """First SIGINT should kill processes but not raise SystemExit."""
    pm = ProcessManager()
    # Calling the handler directly should NOT exit
    pm._sigint_handler(signal.SIGINT, None)
    # If we get here, no SystemExit was raised -- that's the point.


def test_sigint_restores_default_handler():
    """After first SIGINT, handler should be restored to SIG_DFL."""
    pm = ProcessManager()
    assert signal.getsignal(signal.SIGINT) == pm._sigint_handler
    pm._sigint_handler(signal.SIGINT, None)
    assert signal.getsignal(signal.SIGINT) == signal.SIG_DFL


def test_sigterm_exits():
    """SIGTERM should cleanup and exit."""
    pm = ProcessManager()
    with pytest.raises(SystemExit) as exc_info:
        pm._fatal_signal_handler(signal.SIGTERM, None)
    assert exc_info.value.code == 0


def test_cleanup_idempotent():
    """Second cleanup() call should be a no-op."""
    pm = ProcessManager()
    pm.cleanup()
    pm.cleanup()  # Should not raise


def test_uses_reentrant_lock():
    """ProcessManager should use RLock to avoid deadlock in signal handlers."""
    pm = ProcessManager()
    assert isinstance(pm._lock, type(threading.RLock()))
