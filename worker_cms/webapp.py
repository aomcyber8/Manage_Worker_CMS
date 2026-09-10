from __future__ import annotations

import base64
import io
from pathlib import Path

from flask import Flask, jsonify, request, send_file

from .config import Config
from .excel_service import export_excel, export_filename, parse_excel
from .models import CmsSite
from .resign_history import ResignHistoryService
from .selenium_cms import SeleniumCmsAutomation
from .state import StateService
from .workflow import WorkflowService


def create_app(config: Config | None = None, *, state=None, workflow=None, resign_history=None) -> Flask:
    config = config or Config()
    state = state or StateService(config)
    automation = SeleniumCmsAutomation(config)
    workflow = workflow or WorkflowService(state, automation, config)
    resign_history = resign_history or ResignHistoryService(config)
    web_dir = Path(__file__).resolve().parent.parent / "web"
    app = Flask(__name__, static_folder=str(web_dir), static_url_path="")
    app.config.update(MAX_CONTENT_LENGTH=7 * 1024 * 1024, JSON_AS_ASCII=False)
    app.extensions.update(worker_state=state, worker_workflow=workflow)

    @app.before_request
    def local_security():
        port = config.get_int("PORT", 8765)
        expected = {f"localhost:{port}", f"127.0.0.1:{port}"}
        if request.host not in expected:
            return jsonify(error="Host not allowed"), 403
        if request.method == "POST" and request.headers.get("X-CSRF-Token") != state.token:
            return jsonify(error="กรุณาเปิดหน้าเว็บใหม่"), 403

    @app.after_request
    def headers(response):
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Content-Security-Policy"] = "default-src 'self'; script-src 'self'; style-src 'self'; frame-ancestors 'none'; base-uri 'none'"
        return response

    @app.errorhandler(ValueError)
    def bad_request(error):
        return jsonify(error=str(error)), 400

    @app.errorhandler(RuntimeError)
    def conflict(error):
        return jsonify(error=str(error)), 409

    @app.errorhandler(413)
    def too_large(_):
        return jsonify(error="ไฟล์ Excel ต้องไม่เกิน 5 MB"), 413

    @app.errorhandler(Exception)
    def unexpected(error):
        app.logger.exception("Unhandled request error", exc_info=error)
        return jsonify(error="ดำเนินการไม่สำเร็จ กรุณาตรวจสอบข้อมูลและลองใหม่"), 400

    @app.get("/")
    def index():
        return app.send_static_file("index.html")

    @app.get("/api/state")
    def api_state():
        return jsonify(state.snapshot())

    @app.post("/api/preview")
    def preview():
        if state.active:
            raise RuntimeError("มีงานกำลังทำงาน กรุณารอให้จบก่อน")
        data = request.get_json(silent=True) or {}
        site = CmsSite.parse(data.get("site"))
        filename = str(data.get("filename") or "")
        if not filename.casefold().endswith(".xlsx"):
            raise ValueError("เลือกไฟล์ .xlsx เท่านั้น")
        try:
            raw = base64.b64decode(str(data.get("data") or ""), validate=True)
        except Exception as exc:
            raise ValueError("ข้อมูลไฟล์ไม่ถูกต้อง") from exc
        records = parse_excel(raw, int(data.get("header_row") or 1), str(data.get("sheet") or ""))
        state.preview(site, filename, records)
        return jsonify(ok=True)

    @app.post("/api/resign-history")
    def history():
        if state.active:
            raise RuntimeError("มีงานกำลังทำงาน กรุณารอให้จบก่อน")
        data = request.get_json(silent=True) or {}
        site = CmsSite.parse(data.get("site"))
        month = str(data.get("month") or "")
        records = resign_history.load(month)
        state.preview(site, f"resign-history-{month}.xlsx", records)
        return jsonify(ok=True, count=len(records))

    @app.post("/api/start")
    def start():
        data = request.get_json(silent=True) or {}
        workflow.start(CmsSite.parse(data.get("site")), str(data.get("mode") or ""),
                       str(data.get("revision") or ""), str(data.get("confirmation") or ""))
        return jsonify(ok=True)

    @app.post("/api/stop")
    def stop():
        workflow.stop()
        return jsonify(ok=True)

    @app.post("/api/export")
    def export():
        if state.active:
            raise RuntimeError("มีงานกำลังทำงาน กรุณารอให้จบก่อน")
        content = export_excel(list(state.records), list(state.results))
        filename = export_filename(state.filename, CmsSite.parse(state.site))
        return send_file(io.BytesIO(content), as_attachment=True, download_name=filename,
                         mimetype="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")

    return app

