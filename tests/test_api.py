from __future__ import annotations

import base64
from unittest.mock import Mock

import pytest

from conftest import workbook_bytes
from worker_cms.models import CmsSite
from worker_cms.state import StateService
from worker_cms.webapp import create_app


@pytest.fixture
def api(configured):
    state=StateService(configured); workflow=Mock(); history=Mock()
    app=create_app(configured,state=state,workflow=workflow,resign_history=history); app.testing=True
    return app.test_client(), state, workflow, history


def headers(state, host="127.0.0.1:8765"): return {"Host":host,"X-CSRF-Token":state.token}


def test_state_contract(api):
    client,state,_,_=api; response=client.get("/api/state",headers={"Host":"127.0.0.1:8765"})
    assert response.status_code==200 and set(response.json) == {"phase","active","mode","site","filename","revision","cms_actions_allowed","records","results","logs","error","token","configured"}


def test_host_rejected(api):
    client,_,_,_=api; assert client.get("/api/state",headers={"Host":"evil.test"}).status_code==403


def test_csrf_rejected(api):
    client,_,_,_=api; assert client.post("/api/stop",headers={"Host":"127.0.0.1:8765"}).status_code==403


def test_preview(api):
    client,state,_,_=api; raw=workbook_bytes(rows=[["1/4/2026","001","7","นายสมชาย ใจดี","a@b","P","D"]])
    response=client.post("/api/preview",headers=headers(state),json={"site":"SENA","filename":"a.xlsx","data":base64.b64encode(raw).decode(),"header_row":1,"sheet":""})
    assert response.status_code==200 and state.records[0].id=="001"


def test_preview_requires_xlsx(api):
    client,state,_,_=api; response=client.post("/api/preview",headers=headers(state),json={"site":"SENA","filename":"a.xls","data":""})
    assert response.status_code==400 and "xlsx" in response.json["error"]


def test_resign_history(api,record):
    client,state,_,history=api; history.load.return_value=[record]
    response=client.post("/api/resign-history",headers=headers(state),json={"site":"SENA","month":"2026-04"})
    assert response.json=={"ok":True,"count":1} and state.filename=="resign-history-2026-04.xlsx"
    assert state.cms_actions_allowed; history.load.assert_called_once_with("2026-04")


def test_resign_history_year_range_is_export_only(api,record):
    client,state,_,history=api; history.load_year_range.return_value=[record]
    response=client.post("/api/resign-history",headers=headers(state),json={"site":"SENA","period_type":"year-range","start_year":"2024","end_year":"2026"})
    assert response.json=={"ok":True,"count":1} and state.filename=="resign-history-2024-2026.xlsx"
    assert not state.cms_actions_allowed; history.load_year_range.assert_called_once_with("2024","2026")
    exported=client.post("/api/export",headers=headers(state),json={})
    assert exported.status_code==200 and exported.mimetype.endswith("spreadsheetml.sheet")


def test_start_forwards_request(api,record):
    client,state,workflow,_=api; response=client.post("/api/start",headers=headers(state),json={"site":"SENA","mode":"dry-run","revision":"r"})
    assert response.status_code==200; workflow.start.assert_called_once()


def test_stop(api):
    client,state,workflow,_=api; assert client.post("/api/stop",headers=headers(state),json={}).status_code==200; workflow.stop.assert_called_once()


def test_export(api,record):
    client,state,_,_=api; state.preview(CmsSite.parse("SENA"),"a.xlsx",[record]); response=client.post("/api/export",headers=headers(state),json={})
    assert response.status_code==200 and response.mimetype.endswith("spreadsheetml.sheet") and "attachment" in response.headers["Content-Disposition"]
