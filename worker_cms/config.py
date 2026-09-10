from __future__ import annotations

import os
from pathlib import Path

from dotenv import dotenv_values


class Config:
    def __init__(self, values: dict[str, object] | None = None, path: str | Path = ".env"):
        file_values = dotenv_values(path) if Path(path).is_file() else {}
        self._values = {str(k): str(v) for k, v in file_values.items() if v is not None}
        if values:
            self._values.update({str(k): str(v) for k, v in values.items()})

    def get(self, key: str, fallback: str = "") -> str:
        return os.environ.get(key) or self._values.get(key, fallback)

    def get_int(self, key: str, fallback: int) -> int:
        try:
            return int(self.get(key, str(fallback)))
        except ValueError:
            return fallback

    def get_float(self, key: str, fallback: float) -> float:
        try:
            return float(self.get(key, str(fallback)))
        except ValueError:
            return fallback

    def get_bool(self, key: str, fallback: bool = False) -> bool:
        value = self.get(key, str(fallback)).strip().lower()
        return value in {"1", "true", "yes", "on"}

    @property
    def configured(self) -> bool:
        return bool(self.get("CMS_USERNAME").strip() and self.get("CMS_PASSWORD").strip())

