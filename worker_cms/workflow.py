from __future__ import annotations

import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Callable

from .config import Config
from .models import CmsSite, WorkerRecord, WorkerResult, WorkerStatus
from .selenium_cms import SeleniumCmsAutomation
from .state import StateService


class WorkflowService:
    def __init__(self, state: StateService, automation: SeleniumCmsAutomation, config: Config):
        self.state, self.automation, self.config = state, automation, config
        self.executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="worker-cms")
        self.stop_event = threading.Event()

    def start(self, site: CmsSite, mode: str, revision: str, confirmation: str = "") -> None:
        if mode not in {"dry-run", "deactivate"}:
            raise ValueError("โหมดไม่ถูกต้อง")
        if mode == "deactivate" and confirmation != "DEACTIVATE":
            raise ValueError("พิมพ์ DEACTIVATE เพื่อยืนยันการปิดสิทธิ์ใช้งาน")
        if not self.config.configured:
            raise ValueError("กรุณาตั้งค่าบัญชี CMS ใน .env ก่อน")
        records, existing = self.state.begin(site, mode, revision)
        self.stop_event.clear()
        self.executor.submit(self._execute, records, mode == "dry-run", site, self.state.log, True, existing)

    def stop(self) -> None:
        self.stop_event.set()

    def run_blocking(self, records: list[WorkerRecord], dry_run: bool, site: CmsSite, log: Callable[[str], None]) -> list[WorkerResult]:
        self.stop_event.clear()
        return self._execute(records, dry_run, site, log, False, [])

    def _execute(self, records, dry_run, site, log, web, initial):
        completed = {result.row: result for result in initial}
        try:
            with self.automation.open(site, log) as session:
                if dry_run:
                    self._process_pass(session, records, completed, web)
                else:
                    if not initial:
                        log("ช่วงค้นหา: ทดสอบรายการทั้งหมดสำหรับ command line")
                        self._process_pass(session, records, completed, web)
                    found = [record for record in records if completed.get(record.row) and completed[record.row].status == WorkerStatus.SKIPPED]
                    log(f"ปิดสิทธิ์ใช้งาน {len(found)} รายการที่มีผล Dry Run เป็นพบข้อมูล")
                    if web: self.state.set_phase("deactivating")
                    for index, record in enumerate(found, 1):
                        if self.stop_event.is_set(): break
                        result = session.process(record, index, len(found), False)
                        if result.status == WorkerStatus.NOT_FOUND:
                            message = f"Error: รอบปิดสิทธิ์ค้นหา '{record.search_name}' ไม่พบ ทั้งที่ผล Dry Run เป็นพบข้อมูล"
                            log(message)
                            result = WorkerResult(record.row, record.code, record.name, record.search_name, WorkerStatus.FAILED, message)
                        self._put(completed, result, web)
                        self._fail_if_needed(result)
                        self._delay(index < len(found))
                if web: self.state.set_phase("finishing")
                session.logout()
                if web: self.state.complete(self.stop_event.is_set())
        except Exception as exc:
            log(str(exc) or type(exc).__name__)
            if web:
                self.state.fail("งานหยุดเนื่องจากข้อผิดพลาด ดูรายละเอียดในบันทึกการทำงาน")
            else:
                raise
        return [completed[record.row] for record in records if record.row in completed]

    def _process_pass(self, session, records, completed, web):
        for index, record in enumerate(records, 1):
            if self.stop_event.is_set(): break
            result = session.process(record, index, len(records), True)
            self._put(completed, result, web)
            self._fail_if_needed(result)
            self._delay(index < len(records))

    def _put(self, completed, result, web):
        completed[result.row] = result
        if web: self.state.add_result(result)

    @staticmethod
    def _fail_if_needed(result):
        if result.status == WorkerStatus.FAILED:
            raise RuntimeError("หยุดเมื่อพบข้อผิดพลาด โปรดตรวจสอบผลใน CMS ก่อนเริ่มใหม่")

    def _delay(self, has_next):
        if has_next and not self.stop_event.is_set():
            self.stop_event.wait(max(0, self.config.get_float("DELAY_BETWEEN_RECORDS", 0.2)))

