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

def test_load_single_year_includes_boundaries_and_sorts(configured):
    rows=[
        ["1/1/2024","1","1","คน หนึ่ง","","",""],
        ["31/12/2024","2","2","คน สอง","","",""],
        ["1/1/2025","3","3","คน สาม","","",""],
        ["31/12/2023","4","4","คน สี่","","",""],
    ]
    records=ResignHistoryService(configured,lambda _:html(rows)).load_year_range("2024","2024")
    assert [item.termination_date for item in records] == ["31/12/2024","01/01/2024"]


def test_load_year_range_keeps_duplicate_names_and_empty_years(configured):
    rows=[
        ["1/4/2026","1","1","นายสมชาย ใจดี","","",""],
        ["1/4/2024","2","2","นายสมชาย ใจดี","","",""],
    ]
    records=ResignHistoryService(configured,lambda _:html(rows)).load_year_range(2024,2026)
    assert len(records) == 2 and records[0].search_name == records[1].search_name


def test_month_still_rejects_duplicate_names(configured):
    rows=[
        ["2/4/2026","1","1","นายสมชาย ใจดี","","",""],
        ["1/4/2026","2","2","นายสมชาย ใจดี","","",""],
    ]
    with pytest.raises(ValueError,match="ชื่อซ้ำ"): ResignHistoryService(configured,lambda _:html(rows)).load("2026-04")


@pytest.mark.parametrize(("start","end","message"),[("20x4","2026","4 หลัก"),("2027","2026","ไม่มากกว่า")])
def test_invalid_year_range(configured,start,end,message):
    with pytest.raises(ValueError,match=message): ResignHistoryService(configured,lambda _:"").load_year_range(start,end)


def test_empty_year_range(configured):
    service=ResignHistoryService(configured,lambda _:html([["1/1/2023","1","1","ก ข","","",""]]))
    with pytest.raises(ValueError,match="ช่วงปี 2024-2026"): service.load_year_range(2024,2026)


def test_year_range_pagination_stops_after_older_page(configured):
    called=[]
    pages={
        1:html([["1/1/2027","9","9","ใหม่ มาก","","",""]],(1,2,3,4)),
        2:html([["1/6/2025","1","1","ก ข","","",""]],(1,2,3,4)),
        3:html([["31/12/2023","2","2","เก่า มาก","","",""]],(1,2,3,4)),
    }
    def fetch(page): called.append(page); return pages[page]
    records=ResignHistoryService(configured,fetch).load_year_range(2024,2025)
    assert [item.id for item in records] == ["1"] and called == [1,2,3]
