from __future__ import annotations

import io

import pytest
from openpyxl import Workbook

from worker_cms.config import Config
from worker_cms.models import WorkerRecord


@pytest.fixture
def configured():
    return Config({"CMS_USERNAME": "user", "CMS_PASSWORD": "pass", "WAIT_TIMEOUT": "1", "STATUS_SETTLE_MILLIS": "0"}, path="missing.env")


@pytest.fixture
def record():
    return WorkerRecord(2, "001", "สมชาย ใจดี", "01/04/2026", "001", "0007", "นายสมชาย ใจดี", "x@example.com", "หัวหน้า", "บุคคล")


def workbook_bytes(headers=None, rows=None):
    book = Workbook(); sheet = book.active
    sheet.append(headers or ["วันที่พ้นสภาพ", "ID", "No", "ชื่อ", "E-mail", "ตำแหน่ง", "แผนก"])
    for row in rows or []: sheet.append(row)
    output = io.BytesIO(); book.save(output); book.close(); return output.getvalue()

