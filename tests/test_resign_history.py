import pytest

from worker_cms.resign_history import ResignHistoryService


def html(rows, pages=(1,)):
    options="".join(f"<option>{p}</option>" for p in pages)
    body="".join("<tr>"+"".join(f"<td>{cell}</td>" for cell in row)+"</tr>" for row in rows)
    return f"<table id='data'>{body}<tr><td colspan='7'><select name='page'>{options}</select></td></tr></table>"


def test_parse_history_table(configured):
    pages, rows=ResignHistoryService.parse_page(html([["1/4/2026","001","7","นายสมชาย ใจดี","a@b","P","D"]],(1,2)))
    assert pages == 2 and rows[0].id == "001"


def test_missing_table(configured):
    with pytest.raises(RuntimeError,match="ไม่พบตาราง"): ResignHistoryService.parse_page("<html></html>")


def test_load_filters_month(configured):
    service=ResignHistoryService(configured,lambda _:html([["1/4/2026","001","7","นายสมชาย ใจดี","a@b","P","D"],["1/3/2026","002","8","คน เก่า","","",""]]))
    records=service.load("2026-04")
    assert len(records)==1 and records[0].search_name=="สมชาย ใจดี"


def test_load_multiple_pages(configured):
    pages={1:html([["30/4/2026","1","1","ก ข","","",""]],(1,2)),2:html([["1/4/2026","2","2","ค ง","","",""]],(1,2))}
    assert len(ResignHistoryService(configured,lambda page:pages[page]).load("2026-04")) == 2


def test_invalid_month(configured):
    with pytest.raises(ValueError,match="เดือนและปี"): ResignHistoryService(configured,lambda _:"").load("bad")


def test_empty_month(configured):
    service=ResignHistoryService(configured,lambda _:html([["1/3/2026","1","1","ก ข","","",""]]))
    with pytest.raises(ValueError,match="ไม่พบข้อมูล"): service.load("2026-04")

