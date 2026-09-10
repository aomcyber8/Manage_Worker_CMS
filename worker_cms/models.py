from __future__ import annotations

from dataclasses import asdict, dataclass
from enum import StrEnum


class WorkerStatus(StrEnum):
    SUCCESS = "SUCCESS"
    SKIPPED = "SKIPPED"
    NOT_FOUND = "NOT_FOUND"
    FAILED = "FAILED"


@dataclass(frozen=True, slots=True)
class WorkerRecord:
    row: int
    code: str
    search_name: str
    termination_date: str
    id: str
    no: str
    name: str
    email: str
    position: str
    department: str

    def to_dict(self) -> dict:
        return asdict(self)


@dataclass(frozen=True, slots=True)
class WorkerResult:
    row: int
    code: str
    name: str
    search_name: str
    status: WorkerStatus
    message: str

    def to_dict(self) -> dict:
        value = asdict(self)
        value["status"] = self.status.value
        return value


@dataclass(frozen=True, slots=True)
class CmsSite:
    name: str
    base_url: str

    @property
    def employee_url(self) -> str:
        return f"{self.base_url}/Common/Employee"

    @classmethod
    def parse(cls, value: object) -> "CmsSite":
        key = str(value or "").strip().upper()
        if key == "SENA":
            return cls("SENA", "https://sena.on.lk")
        if key == "SENX":
            return cls("SENX", "https://senx.on.lk")
        raise ValueError("กรุณาเลือก SENA หรือ SENX")

