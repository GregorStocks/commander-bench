"""CLI entrypoint for cleaning up ai-harness orphan processes."""

from puppeteer.process_manager import cleanup_orphans


def main():
    cleanup_orphans()


if __name__ == "__main__":
    main()
