"""Process lifecycle management with signal handling."""

import os
import signal
import subprocess
import sys
import threading
from pathlib import Path

import psutil


class ProcessManager:
    """Manages subprocess lifecycle with proper cleanup on signals."""

    def __init__(self):
        self._processes: list[subprocess.Popen] = []
        self._lock = threading.Lock()
        self._setup_signal_handlers()

    def _setup_signal_handlers(self):
        """Register signal handlers for SIGINT and SIGTERM."""
        signal.signal(signal.SIGINT, self._signal_handler)
        signal.signal(signal.SIGTERM, self._signal_handler)

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
        """Start a subprocess and track it for cleanup."""
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
            start_new_session=True,
        )

        with self._lock:
            self._processes.append(proc)

        return proc

    def _kill_tree(self, pid: int):
        """Kill a process and all its children."""
        try:
            parent = psutil.Process(pid)
            children = parent.children(recursive=True)
            for child in children:
                child.terminate()
            parent.terminate()

            # Wait briefly then force kill if needed
            gone, alive = psutil.wait_procs(children + [parent], timeout=3)
            for p in alive:
                p.kill()
        except psutil.NoSuchProcess:
            pass

    def cleanup(self):
        """Terminate all tracked processes and their children."""
        with self._lock:
            for proc in self._processes:
                if proc.poll() is None:  # Still running
                    print(f"Killing process tree rooted at PID {proc.pid}")
                    self._kill_tree(proc.pid)
            self._processes.clear()
