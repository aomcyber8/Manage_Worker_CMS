from __future__ import annotations

import argparse
import sys
from datetime import datetime
from pathlib import Path
from urllib.parse import urlparse

from .config import Config
from .excel_service import parse_excel
from .models import CmsSite, WorkerStatus
from .selenium_cms import SeleniumCmsAutomation
from .state import StateService
from .workflow import WorkflowService


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(prog="worker-cms")
    result.add_argument("--cli", action="store_true", help=argparse.SUPPRESS)
    result.add_argument("--dry-run", action="store_true")
    result.add_argument("--file")
    result.add_argument("--site", choices=["SENA", "SENX"])
    result.add_argument("--header-row", type=int)
    result.add_argument("--start-row", type=int)
    result.add_argument("--sheet")
    result.add_argument("--yes", action="store_true")
    return result


def run_cli(argv=None, config=None) -> int:
    args = parser().parse_args(argv)
    config = config or Config()
    if not config.configured:
        print("กรุณาตั้งค่า CMS_USERNAME และ CMS_PASSWORD ในไฟล์ .env", file=sys.stderr); return 1
    source = Path(args.file or config.get("EXCEL_FILE_PATH", "./data/worker_list.xlsx"))
    if not source.is_file():
        print(f"ไม่พบไฟล์ Excel: {source.resolve()}", file=sys.stderr); return 1
    if args.site:
        site = CmsSite.parse(args.site)
    else:
        site = CmsSite.parse("SENX" if "senx.on.lk" in config.get("CMS_URL", "https://sena.on.lk").casefold() else "SENA")
    header_row = args.header_row if args.header_row is not None else max(1, (args.start_row or config.get_int("EXCEL_START_ROW", 2)) - 1)
    sheet = args.sheet if args.sheet is not None else config.get("EXCEL_SHEET_NAME")
    try:
        records = parse_excel(source.read_bytes(), header_row, sheet)
        print(f"CMS {site.name} | {len(records)} รายการ | {'DRY RUN' if args.dry_run else 'DEACTIVATE'}")
        if not args.dry_run and not args.yes and input("ต้องการดำเนินการต่อหรือไม่? (y/N): ").strip().casefold() not in {"y", "yes"}:
            print("ยกเลิกการทำงาน"); return 0
        Path("logs").mkdir(exist_ok=True)
        log_file = Path("logs") / f"delete_worker_{datetime.now():%Y%m%d_%H%M%S}.log"
        with log_file.open("x", encoding="utf-8") as output:
            def log(message):
                print(message); print(message, file=output, flush=True)
            workflow = WorkflowService(StateService(config), SeleniumCmsAutomation(config), config)
            results = workflow.run_blocking(records, args.dry_run, site, log)
            counts = {status: sum(item.status == status for item in results) for status in WorkerStatus}
            log(f"สรุป: ทั้งหมด {len(results)} | สำเร็จ {counts[WorkerStatus.SUCCESS]} | ทดสอบผ่าน {counts[WorkerStatus.SKIPPED]} | ไม่พบ {counts[WorkerStatus.NOT_FOUND]}")
            log(f"Log file: {log_file.resolve()}")
        return 0
    except Exception as exc:
        print(str(exc), file=sys.stderr); return 1

