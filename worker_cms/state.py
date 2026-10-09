from __future__ import annotations

import secrets
import threading
import uuid

from .config import Config
from .models import CmsSite, WorkerRecord, WorkerResult, WorkerStatus


class StateService:
    def __init__(self, config: Config):
        self.config = config
        self._lock = threading.RLock()
        self.phase = "idle"
        self.active = False
        self.mode = "dry-run"
        self.site = ""
        self.filename = ""
        self.revision = ""
        self.cms_actions_allowed = True
        self.records: list[WorkerRecord] = []
        self.results: list[WorkerResult] = []
        self.logs: list[str] = []
        self.error = ""
        self.token = secrets.token_urlsafe(32)

    def snapshot(self) -> dict:
        with self._lock:
            return {
                "phase": self.phase, "active": self.active, "mode": self.mode, "site": self.site,
                "filename": self.filename, "revision": self.revision,
                "cms_actions_allowed": self.cms_actions_allowed,
                "records": [item.to_dict() for item in self.records],
                "results": [item.to_dict() for item in self.results],
                "logs": list(self.logs), "error": self.error, "token": self.token,
                "configured": self.config.configured,
            }

    def preview(self, site: CmsSite, filename: str, records: list[WorkerRecord], *, cms_actions_allowed: bool = True) -> None:
        with self._lock:
            if self.active:
                raise RuntimeError("มีงานกำลังทำงาน กรุณารอให้จบก่อน")
            self.phase, self.active, self.mode = "ready", False, "dry-run"
            self.site, self.filename = site.name, filename
            self.cms_actions_allowed = bool(cms_actions_allowed)
            self.revision = str(uuid.uuid4())
            self.records, self.results, self.logs, self.error = list(records), [], [], ""

    def begin(self, site: CmsSite, mode: str, revision: str) -> tuple[list[WorkerRecord], list[WorkerResult]]:
        with self._lock:
            if self.active:
                raise RuntimeError("มีงานกำลังทำงาน กรุณารอให้จบก่อน")
            if not self.cms_actions_allowed:
                raise ValueError("ข้อมูลช่วงปีใช้สำหรับ Export Excel เท่านั้น")
            if self.site != site.name:
                raise ValueError("ระบบ CMS เปลี่ยนไป กรุณาอ่านไฟล์และตรวจรายการใหม่")
            if not self.records or self.revision != revision:
                raise ValueError("กรุณาอ่านไฟล์และตรวจรายการใหม่")
            if mode == "deactivate":
                self._validate_dry_run()
                self.phase, self.active, self.mode = "deactivating", True, mode
                self.logs, self.error = [], ""
            else:
                self.phase, self.active, self.mode = "running", True, mode
                self.results, self.logs, self.error = [], [], ""
            return list(self.records), list(self.results)

    def _validate_dry_run(self) -> None:
        valid = {WorkerStatus.SKIPPED, WorkerStatus.NOT_FOUND}
        rows = {record.row for record in self.records}
        result_rows = {result.row for result in self.results}
        if self.mode != "dry-run" or self.phase != "done" or len(self.results) != len(self.records) or result_rows != rows:
            raise ValueError("กรุณาทดสอบรายการให้เสร็จครบก่อนปิดสิทธิ์ใช้งาน")
        if any(result.status not in valid for result in self.results):
            raise ValueError("ผลทดสอบรายการไม่สมบูรณ์ กรุณาทดสอบรายการใหม่")
        if not any(result.status == WorkerStatus.SKIPPED for result in self.results):
            raise ValueError("ไม่มีรายการที่พบข้อมูลสำหรับปิดสิทธิ์ใช้งาน")

    def add_result(self, result: WorkerResult) -> None:
        with self._lock:
            for index, existing in enumerate(self.results):
                if existing.row == result.row:
                    self.results[index] = result
                    return
            self.results.append(result)

    def log(self, message: object) -> None:
        with self._lock:
            self.logs.append(str(message))
            self.logs = self.logs[-400:]

    def set_phase(self, value: str) -> None:
        with self._lock:
            self.phase = value

    def complete(self, stopped: bool) -> None:
        with self._lock:
            self.phase, self.active = ("stopped" if stopped else "done"), False

    def fail(self, message: str) -> None:
        with self._lock:
            self.phase, self.active, self.error = "failed", False, message
