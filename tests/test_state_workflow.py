from __future__ import annotations

from unittest.mock import Mock

import pytest

from worker_cms.models import CmsSite, WorkerResult, WorkerStatus
from worker_cms.state import StateService
from worker_cms.workflow import WorkflowService


def result(record, status): return WorkerResult(record.row, record.code, record.name, record.search_name, status, status.value)


def test_preview_sets_revision(configured, record):
    state=StateService(configured); state.preview(CmsSite.parse("SENA"),"a.xlsx",[record]); snap=state.snapshot()
    assert snap["phase"] == "ready" and snap["revision"] and snap["records"][0]["search_name"] == "สมชาย ใจดี"


def test_begin_dry_run_clears_results(configured, record):
    state=StateService(configured); state.preview(CmsSite.parse("SENA"),"a.xlsx",[record]); state.results=[result(record,WorkerStatus.NOT_FOUND)]
    state.begin(CmsSite.parse("SENA"),"dry-run",state.revision)
    assert state.active and state.results == []


def test_year_range_state_rejects_cms_actions(configured, record):
    state=StateService(configured); state.preview(CmsSite.parse("SENA"),"range.xlsx",[record],cms_actions_allowed=False)
    assert not state.snapshot()["cms_actions_allowed"]
    with pytest.raises(ValueError,match="Export Excel เท่านั้น"): state.begin(CmsSite.parse("SENA"),"dry-run",state.revision)


def test_wrong_revision(configured, record):
    state=StateService(configured); state.preview(CmsSite.parse("SENA"),"a.xlsx",[record])
    with pytest.raises(ValueError, match="อ่านไฟล์"): state.begin(CmsSite.parse("SENA"),"dry-run","old")


def test_wrong_site(configured, record):
    state=StateService(configured); state.preview(CmsSite.parse("SENA"),"a.xlsx",[record])
    with pytest.raises(ValueError, match="CMS เปลี่ยน"): state.begin(CmsSite.parse("SENX"),"dry-run",state.revision)


def test_deactivate_requires_complete_dry_run(configured, record):
    state=StateService(configured); state.preview(CmsSite.parse("SENA"),"a.xlsx",[record])
    with pytest.raises(ValueError, match="ทดสอบรายการ"): state.begin(CmsSite.parse("SENA"),"deactivate",state.revision)


def test_deactivate_preserves_dry_results(configured, record):
    state=StateService(configured); state.preview(CmsSite.parse("SENA"),"a.xlsx",[record]); state.mode="dry-run"; state.phase="done"; state.results=[result(record,WorkerStatus.SKIPPED)]
    _, existing=state.begin(CmsSite.parse("SENA"),"deactivate",state.revision)
    assert existing[0].status == WorkerStatus.SKIPPED


def test_add_result_replaces_by_row(configured, record):
    state=StateService(configured); state.add_result(result(record,WorkerStatus.SKIPPED)); state.add_result(result(record,WorkerStatus.SUCCESS))
    assert len(state.results) == 1 and state.results[0].status == WorkerStatus.SUCCESS


class FakeSession:
    def __init__(self, outcomes): self.outcomes=list(outcomes); self.calls=[]; self.logged_out=False
    def __enter__(self): return self
    def __exit__(self,*_): pass
    def process(self, record, index, total, dry_run): self.calls.append((record.row,dry_run)); return result(record,self.outcomes.pop(0))
    def logout(self): self.logged_out=True


class FakeAutomation:
    def __init__(self, session): self.session=session
    def open(self,site,log): return self.session


def test_blocking_dry_run(configured, record):
    session=FakeSession([WorkerStatus.SKIPPED]); workflow=WorkflowService(StateService(configured),FakeAutomation(session),configured)
    assert workflow.run_blocking([record],True,CmsSite.parse("SENA"),lambda _:None)[0].status == WorkerStatus.SKIPPED
    assert session.calls == [(2,True)] and session.logged_out


def test_cli_deactivate_searches_then_deactivates_found_only(configured, record):
    missing=record.__class__(3,"2","คน หาย","","2","","คน หาย","","","")
    session=FakeSession([WorkerStatus.SKIPPED,WorkerStatus.NOT_FOUND,WorkerStatus.SUCCESS]); workflow=WorkflowService(StateService(configured),FakeAutomation(session),configured)
    results=workflow.run_blocking([record,missing],False,CmsSite.parse("SENA"),lambda _:None)
    assert session.calls == [(2,True),(3,True),(2,False)]
    assert [item.status for item in results] == [WorkerStatus.SUCCESS,WorkerStatus.NOT_FOUND]


def test_failed_stops_pass(configured, record):
    session=FakeSession([WorkerStatus.FAILED]); workflow=WorkflowService(StateService(configured),FakeAutomation(session),configured)
    with pytest.raises(RuntimeError, match="หยุดเมื่อพบข้อผิดพลาด"): workflow.run_blocking([record],True,CmsSite.parse("SENA"),lambda _:None)


def test_invalid_mode(configured):
    workflow=WorkflowService(StateService(configured),Mock(),configured)
    with pytest.raises(ValueError,match="โหมด"): workflow.start(CmsSite.parse("SENA"),"bad","x")


def test_confirmation_required(configured):
    workflow=WorkflowService(StateService(configured),Mock(),configured)
    with pytest.raises(ValueError,match="DEACTIVATE"): workflow.start(CmsSite.parse("SENA"),"deactivate","x")
