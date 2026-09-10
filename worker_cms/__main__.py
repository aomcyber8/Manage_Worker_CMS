from __future__ import annotations

import sys

from waitress import serve

from .cli import run_cli
from .config import Config
from .webapp import create_app


def main() -> int:
    if "--cli" in sys.argv:
        return run_cli(sys.argv[1:])
    config = Config()
    serve(create_app(config), host="127.0.0.1", port=config.get_int("PORT", 8765), threads=6)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
