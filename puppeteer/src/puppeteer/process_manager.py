"""Process lifecycle management with signal handling."""

import atexit
import os
import signal
import subprocess
import sys
import threading
from pathlib import Path

import psutil


# Default location for PID file (relative to cwd)
PID_FILE_PATH = Path(".context/ai-harness-logs/harness.pids")


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


def cleanup_orphans(pid_file: Path = PID_FILE_PATH):
    """Kill any processes left over from a previous harness run.

    Uses two strategies:
    1. PID-file based: read tracked PIDs and kill verified ones (fast, targeted).
    2. pgrep fallback: find processes by command-line markers (xmage.* system
       properties, puppeteer.* module names).  This catches orphans when the
       PID file was lost (SIGKILL, crash, etc.) or when child Java processes
       outlived their tracked Python parents.
    """
    # Strategy 1: PID-file based cleanup
    killed_pids: set[int] = set()
    if pid_file.exists():
        try:
            with open(pid_file) as f:
                for line in f:
                    try:
                        pid = int(line.strip())
                        proc = psutil.Process(pid)
                        # Verify this is actually one of our processes
                        env = proc.environ()
                        if env.get("XMAGE_AI_HARNESS") == "1":
                            print(f"Killing orphaned process {pid}")
                            kill_tree(pid)
                            killed_pids.add(pid)
                    except (psutil.NoSuchProcess, ValueError, psutil.AccessDenied):
                        pass
        except OSError:
            pass
        finally:
            try:
                pid_file.unlink(missing_ok=True)
            except OSError:
                pass

    # Strategy 2: find orphans by command-line markers using pgrep.
    # We avoid psutil.environ() entirely because it can hang indefinitely
    # on macOS (even for java/python processes).  All harness Java processes
    # have "-Dxmage.*" system properties in their command line, and harness
    # Python scripts run as "puppeteer.sleepwalker" / "puppeteer.pilot" / etc.
    my_pid = os.getpid()
    try:
        result = subprocess.run(
            ["pgrep", "-f", r"xmage[.]|puppeteer[.](sleepwalker|pilot|chatterbox)"],
            capture_output=True, text=True, timeout=5,
        )
        for line in result.stdout.strip().splitlines():
            try:
                pid = int(line.strip())
                if pid != my_pid and pid not in killed_pids:
                    print(f"Killing orphaned process {pid}")
                    kill_tree(pid)
            except ValueError:
                pass
    except (subprocess.TimeoutExpired, OSError):
        pass


class ProcessManager:
    """Manages subprocess lifecycle with proper cleanup on signals.

    Ensures all child processes are terminated when:
    - The parent receives SIGINT, SIGTERM, or SIGHUP
    - The parent exits normally
    - The parent exits due to an unhandled exception

    Also cleans up orphaned processes from previous runs on startup.
    """

    def __init__(self):
        self._processes: list[subprocess.Popen] = []
        self._lock = threading.Lock()
        self._cleaned_up = False
        self._pid_file = PID_FILE_PATH

        # Clean up any orphaned processes from previous runs
        self._cleanup_orphans()

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

    def _cleanup_orphans(self):
        """Kill any processes left over from a previous harness run."""
        cleanup_orphans(self._pid_file)

    def _write_pid_file(self):
        """Write current tracked PIDs to file for orphan cleanup."""
        try:
            self._pid_file.parent.mkdir(parents=True, exist_ok=True)
            with open(self._pid_file, "w") as f:
                for proc in self._processes:
                    f.write(f"{proc.pid}\n")
        except OSError:
            pass

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
        # Mark all harness-managed processes so cleanup_orphans() can find them
        # even without a PID file.  This also propagates to grandchild processes
        # (e.g. Java skeletons spawned by sleepwalker/pilot Python scripts).
        merged_env["XMAGE_AI_HARNESS"] = "1"
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
            self._write_pid_file()

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

            # Remove PID file since we've cleaned up
            try:
                self._pid_file.unlink(missing_ok=True)
            except OSError:
                pass
