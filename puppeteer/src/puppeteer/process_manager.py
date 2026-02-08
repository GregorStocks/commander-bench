"""Process lifecycle management with signal handling."""

import atexit
import os
import signal
import subprocess
import sys
import threading
from pathlib import Path

import psutil


def kill_tree(pid: int):
    """Kill a process and all its children."""
    try:
        parent = psutil.Process(pid)

        # If the target process is in a different process group, try
        # terminating the entire group (avoid killing our own group).
        if hasattr(os, "getpgrp") and hasattr(os, "getpgid") and hasattr(os, "killpg"):
            try:
                parent_pgid = os.getpgrp()
                child_pgid = os.getpgid(pid)
                if child_pgid != parent_pgid:
                    os.killpg(child_pgid, signal.SIGTERM)
            except (OSError, ProcessLookupError):
                pass

        children = parent.children(recursive=True)

        # Terminate children first, then parent
        for child in children:
            try:
                child.terminate()
            except psutil.NoSuchProcess:
                pass

        try:
            parent.terminate()
        except psutil.NoSuchProcess:
            pass

        # Wait briefly then force kill if needed
        gone, alive = psutil.wait_procs(children + [parent], timeout=3)
        for p in alive:
            try:
                p.kill()
            except psutil.NoSuchProcess:
                pass
    except psutil.NoSuchProcess:
        pass


class ProcessManager:
    """Manages subprocess lifecycle with proper cleanup on signals.

    Ensures all child processes are terminated when:
    - The parent receives SIGINT, SIGTERM, or SIGHUP
    - The parent exits normally
    - The parent exits due to an unhandled exception
    """

    def __init__(self):
        self._processes: list[subprocess.Popen] = []
        self._lock = threading.Lock()
        self._cleaned_up = False

        self._setup_signal_handlers()
        # Register atexit handler for cleanup on normal exit or unhandled exceptions
        atexit.register(self.cleanup)

    def _setup_signal_handlers(self):
        """Register signal handlers for SIGINT, SIGTERM, and SIGHUP."""
        signal.signal(signal.SIGINT, self._signal_handler)
        signal.signal(signal.SIGTERM, self._signal_handler)
        if hasattr(signal, "SIGHUP"):
            signal.signal(signal.SIGHUP, self._signal_handler)

    def _signal_handler(self, signum, frame):
        """Handle termination signals by cleaning up all processes."""
        print(f"\nReceived signal {signum}, stopping all processes...")
        self.cleanup()
        sys.exit(0)

    def start_process(
        self,
        args: list[str],
        cwd: Path | None = None,
        env: dict[str, str] | None = None,
        log_file: Path | None = None,
    ) -> subprocess.Popen:
        """Start a subprocess and track it for cleanup.

        Processes are kept in the same process group as the parent so they
        receive signals when the parent is killed.
        """
        merged_env = os.environ.copy()
        if env:
            merged_env.update(env)

        stdout = open(log_file, "w") if log_file else subprocess.PIPE
        stderr = subprocess.STDOUT if log_file else subprocess.PIPE

        proc = subprocess.Popen(
            args,
            cwd=cwd,
            env=merged_env,
            stdout=stdout,
            stderr=stderr,
            # Don't use start_new_session=True - keep processes in same group
            # so they receive signals when parent is killed
        )

        with self._lock:
            self._processes.append(proc)

        return proc

    def _kill_tree(self, pid: int):
        """Kill a process and all its children."""
        kill_tree(pid)

    def cleanup(self):
        """Terminate all tracked processes and their children."""
        with self._lock:
            if self._cleaned_up:
                return
            self._cleaned_up = True

            for proc in self._processes:
                if proc.poll() is None:  # Still running
                    print(f"Killing process tree rooted at PID {proc.pid}")
                    self._kill_tree(proc.pid)

            self._processes.clear()
