from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime
from urllib.parse import parse_qsl, urlencode, urlparse, urlunparse

import requests
from bs4 import BeautifulSoup

from .config import Config
from .models import WorkerRecord
from .names import name_key, normalize_name


@dataclass(frozen=True, slots=True)
class SourceRow:
    date: date
    id: str
    no: str
    name: str
    email: str
    position: str
    department: str


def _clean(value: object) -> str:
    return " ".join(str(value or "").replace("\xa0", " ").replace("\u200b", "").replace("\ufeff", "").split())


class ResignHistoryService:
    def __init__(self, config: Config, fetcher=None):
        self.config = config
        self.fetcher = fetcher or self._http_fetch

    def _http_fetch(self, page: int) -> str:
        source = self.config.get("RESIGN_HISTORY_URL", "http://192.168.0.161/resign/index2.php")
        parsed = urlparse(source)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            raise ValueError("RESIGN_HISTORY_URL ต้องเป็น http หรือ https")
        query = dict(parse_qsl(parsed.query))
        query["page"] = str(page)
        url = urlunparse(parsed._replace(query=urlencode(query)))
        timeout = max(3, self.config.get_int("RESIGN_HISTORY_TIMEOUT_SECONDS", 15))
        response = requests.get(url, timeout=timeout, headers={"Accept": "text/html"})
        if response.status_code != 200:
            raise RuntimeError(f"เว็บข้อมูลผู้ลาออกตอบกลับ HTTP {response.status_code}")
        response.encoding = response.apparent_encoding or "utf-8"
        return response.text

    @staticmethod
    def parse_page(html: str) -> tuple[int, list[SourceRow]]:
        soup = BeautifulSoup(html or "", "html.parser")
        table = soup.select_one("table#data")
        if table is None:
            raise RuntimeError("ไม่พบตารางประวัติผู้ที่ลาออกแล้วในหน้าเว็บ")
        pages = [int(item.get_text(strip=True)) for item in table.select("select[name=page] option") if item.get_text(strip=True).isdigit()]
        rows: list[SourceRow] = []
        for tr in table.select("tr"):
            cells = tr.find_all("td", recursive=False)
            if len(cells) < 7:
                continue
            try:
                row_date = datetime.strptime(_clean(cells[0].get_text(" ")), "%d/%m/%Y").date()
            except ValueError:
                continue
            values = [_clean(cell.get_text(" ")) for cell in cells[1:7]]
            rows.append(SourceRow(row_date, *values))
        return max(pages, default=1), rows

    def load(self, month: str) -> list[WorkerRecord]:
        try:
            selected_month = datetime.strptime(month, "%Y-%m").date().replace(day=1)
        except (TypeError, ValueError) as exc:
            raise ValueError("กรุณาระบุเดือนและปีให้ถูกต้อง") from exc
        selected: list[SourceRow] = []
        total_pages = 1
        page = 1
        while page <= min(total_pages, 2_000):
            try:
                parsed_pages, rows = self.parse_page(self.fetcher(page))
            except (ValueError, RuntimeError):
                raise
            except Exception as exc:
                raise RuntimeError(f"เชื่อมต่อเว็บข้อมูลผู้ลาออกไม่สำเร็จที่หน้า {page}") from exc
            if page == 1:
                total_pages = min(parsed_pages, 2_000)
            reached_older = False
            for row in rows:
                row_month = row.date.replace(day=1)
                if row_month == selected_month:
                    selected.append(row)
                if row.date < selected_month:
                    reached_older = True
            if reached_older or page >= total_pages:
                break
            page += 1
        if not selected:
            raise ValueError(f"ไม่พบข้อมูลผู้ลาออกในเดือน {month}")
        return self._convert(selected)

    @staticmethod
    def _convert(source: list[SourceRow]) -> list[WorkerRecord]:
        records: list[WorkerRecord] = []
        seen: dict[str, int] = {}
        for index, row in enumerate(source, 1):
            missing = [label for value, label in ((row.id, "ID"), (row.name, "ชื่อ")) if not value]
            if missing:
                raise ValueError(f"ข้อมูลเว็บรายการที่ {index} ไม่มีข้อมูล: {', '.join(missing)}")
            search_name = normalize_name(row.name)
            if not search_name:
                raise ValueError(f"ข้อมูลเว็บรายการที่ {index} ไม่มีชื่อสำหรับค้นหา")
            key = name_key(search_name)
            if key in seen:
                raise ValueError(f"ชื่อซ้ำหลังตัดคำนำหน้า: {search_name} (รายการที่ {seen[key]} และ {index})")
            seen[key] = index
            records.append(WorkerRecord(index, row.id, search_name, row.date.strftime("%d/%m/%Y"), row.id,
                                        row.no, row.name, row.email, row.position, row.department))
        return records
