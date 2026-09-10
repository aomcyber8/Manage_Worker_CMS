from __future__ import annotations

import re
import unicodedata

PREFIX = re.compile(r"^(?:นางสาว|น\.ส\.|นาย|นาง)\s*")
SPACES = re.compile(r"[\s\u200b\ufeff]+")


def normalize_name(value: object) -> str:
    text = unicodedata.normalize("NFC", str(value or "")).replace("\xa0", " ")
    text = SPACES.sub(" ", text).strip()
    return PREFIX.sub("", text, count=1).strip()


def name_key(value: object) -> str:
    return normalize_name(value).casefold()


def surname(value: object) -> str:
    normalized = normalize_name(value)
    return normalized.partition(" ")[2]
