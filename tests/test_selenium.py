from __future__ import annotations

from unittest.mock import Mock

import pytest

from worker_cms.models import CmsSite, WorkerStatus
from worker_cms.selenium_cms import (
    GRID_ROWS, RESULT_CELLS, SAVE, SAVE_CONFIRM, SAVE_CONFIRM_YES, STATUS_INPUT_NEAR_LABEL, STATUS_OFF, STATUS_ON, STATUS_SWITCH_LABELS,
    CmsAccessDeniedError, SearchMatch, SeleniumSession,
)


class Element:
    def __init__(self,text="",attrs=None,selected=False,displayed=True,enabled=True,tag="label",click=None):
        self.text=text; self.attrs=attrs or {}; self.selected=selected; self.displayed=displayed; self.enabled=enabled; self.tag_name=tag; self.mapping={}; self.clicked=0; self.on_click=click
    def is_displayed(self): return self.displayed
    def is_enabled(self): return self.enabled
    def is_selected(self): return self.selected
    def get_attribute(self,name): return self.attrs.get(name)
    def find_elements(self,by,value): return self.mapping.get((by,value),[])
    def get(self,url): self.current_url=url
    def find_element(self,by,value): return self.mapping[(by,value)][0]
    def click(self): self.clicked+=1; self.on_click and self.on_click()
    def clear(self): pass
    def send_keys(self,*_): pass


class Driver:
    def __init__(self):
        self.current_url="https://sena.on.lk/Common/Employee/Form?id=1"; self.page_source=""; self.mapping={}; self.script=[]
    def find_elements(self,by,value): return self.mapping.get((by,value),[])
    def get(self,url): self.current_url=url
    def execute_script(self,*_): return self.script
    def implicitly_wait(self,*_): pass
    def save_screenshot(self,*_): return True


def session(configured,driver=None): return SeleniumSession(driver or Driver(),CmsSite.parse("SENA"),configured,lambda _:None)


def status_fixture(selected=True, use_for=False):
    driver=Driver(); on=Element("Employee"); off=Element("Ex-Employee"); input_element=Element(attrs={"class":"switch-input"},selected=selected,tag="input")
    label=Element(attrs={"class":"switch-label"}); label.mapping[STATUS_ON]=[on]; label.mapping[STATUS_OFF]=[off]
    if use_for:
        label.attrs["for"]="status"; driver.mapping[("id","status")]=[input_element]
    else: label.mapping[STATUS_INPUT_NEAR_LABEL]=[input_element]
    driver.mapping[STATUS_SWITCH_LABELS]=[label]
    return driver,label,input_element


@pytest.mark.parametrize(("selected","expected"),[(True,"Employee"),(False,"Ex-Employee")])
def test_checked_input_is_authoritative(configured,selected,expected):
    driver,_,input_element=status_fixture(selected); control=session(configured,driver)._wait_status_control()
    assert ("Employee" if control.input.is_selected() else "Ex-Employee") == expected


def test_status_supports_for_id(configured):
    driver,label,input_element=status_fixture(True,True)
    assert session(configured,driver)._status_controls()[0].input is input_element


def test_status_ignores_unrelated_switch(configured):
    driver,label,input_element=status_fixture(True); unrelated=Element("Receive from E-mail",attrs={"class":"switch-label"})
    driver.mapping[STATUS_SWITCH_LABELS]=[unrelated,label]
    assert len(session(configured,driver)._status_controls()) == 1


def test_ambiguous_status_rejected(configured):
    driver,label,input_element=status_fixture(True); _,label2,input2=status_fixture(True)
    driver.mapping[STATUS_SWITCH_LABELS]=[label,label2]
    with pytest.raises(RuntimeError,match="มากกว่าหนึ่ง"): session(configured,driver)._wait_status_control()


def test_save_button_uses_exact_btn_save_id(configured):
    assert SAVE == ("id", "btn-save")
    driver=Driver(); button=Element(attrs={"id":"btn-save"},tag="a"); driver.mapping[SAVE]=[button]
    assert session(configured,driver)._save_button() is button


def test_save_ignores_saved_and_print(configured):
    driver=Driver(); driver.mapping[SAVE]=[]
    with pytest.raises(RuntimeError,match="พบ 0 ปุ่ม"): session(configured,driver)._save_button()


def test_duplicate_btn_save_is_rejected(configured):
    driver=Driver(); driver.mapping[SAVE]=[Element(),Element()]
    with pytest.raises(RuntimeError,match="พบ 2 ปุ่ม"): session(configured,driver)._save_button()


def test_access_denied_includes_id(configured):
    driver=Driver(); driver.page_source="You are not authorized. Role is not defined. Access Id : 89"
    with pytest.raises(CmsAccessDeniedError,match="Access Id 89"): session(configured,driver)._reject_access_denied()


def test_dry_run_returns_found_without_detail(configured,record,mocker):
    item=session(configured); search=mocker.patch.object(item,"_search",return_value=[SearchMatch(Element(),Element())]); detail=mocker.patch.object(item,"_open_detail")
    result=item.process(record,1,1,True)
    assert result.status == WorkerStatus.SKIPPED; assert search.call_count == 1; detail.assert_not_called()


def test_single_grid_row_is_found_despite_unrecognized_prefix(configured):
    driver=Driver(); row=Element("1 จ.ส.อ. วัฒนา ก่ำเชียงคำ ผู้ช่วยผู้จัดการอาคาร"); cell=Element("จ.ส.อ. วัฒนา ก่ำเชียงคำ")
    row.mapping[RESULT_CELLS]=[cell]; driver.mapping[GRID_ROWS]=[row]
    rows,matches=session(configured,driver)._read_search_grid("วัฒนา ก่ำเชียงคำ")
    assert rows == [row] and len(matches) == 1 and matches[0].row is row


def test_deactivate_uses_employee_detail_link_from_single_row(configured,record,mocker):
    driver=Driver(); row=Element(); cell=Element("จ.ส.อ. สมชาย ใจดี"); link=Element("จ.ส.อ. สมชาย ใจดี",attrs={"href":"/Common/Employee/Form?id=7"},tag="a")
    row.mapping[RESULT_CELLS]=[cell]; cell.mapping[("css selector","a[href]")]=[link]
    item=session(configured,driver); mocker.patch.object(item,"_search",return_value=[SearchMatch(row,cell)])
    opened=mocker.patch.object(item,"_open_detail",return_value=object()); mocker.patch.object(item,"_deactivate",return_value="Ex-Employee — บันทึกสำเร็จ"); mocker.patch.object(item,"_return_to_list")
    result=item.process(record,1,1,False)
    assert result.status == WorkerStatus.SUCCESS; opened.assert_called_once_with(link)


def test_multiple_grid_rows_still_require_exact_name(configured):
    driver=Driver(); exact_row=Element(); other_row=Element(); exact_cell=Element("นายสมชาย ใจดี"); other_cell=Element("สมชาย ใจดำ")
    exact_row.mapping[RESULT_CELLS]=[exact_cell]; other_row.mapping[RESULT_CELLS]=[other_cell]; driver.mapping[GRID_ROWS]=[exact_row,other_row]
    rows,matches=session(configured,driver)._read_search_grid("สมชาย ใจดี")
    assert rows == [exact_row,other_row] and len(matches) == 1 and matches[0].row is exact_row


def test_name_then_surname_not_found(configured,record,mocker):
    item=session(configured); search=mocker.patch.object(item,"_search",side_effect=[[],[]])
    result=item.process(record,1,1,True)
    assert result.status == WorkerStatus.NOT_FOUND and search.call_args_list[1].args[0] == "ใจดี"


def test_deactivate_checked_switch_saves_after_unchecked(configured,mocker):
    driver,label,input_element=status_fixture(True); button=Element(attrs={"title":"Save Document"},tag="a")
    label.on_click=lambda:setattr(input_element,"selected",False)
    item=session(configured,driver); mocker.patch.object(item,"_save_button",return_value=button); confirmed=mocker.patch.object(item,"_confirm_save"); closed=mocker.patch.object(item,"_close_save_success")
    assert item._deactivate() == "Ex-Employee — บันทึกสำเร็จ"
    assert label.clicked == 1 and button.clicked == 1; confirmed.assert_called_once(); closed.assert_called_once()


def test_deactivate_unchecked_switch_skips_save(configured,mocker):
    driver,label,_=status_fixture(False); item=session(configured,driver); save=mocker.patch.object(item,"_save_button")
    assert item._deactivate() == "Ex-Employee — เป็นสถานะนี้อยู่แล้ว"
    save.assert_not_called(); assert label.clicked == 0


def test_transient_access_denied_retries(configured,mocker):
    driver=Driver(); item=session(configured,driver)
    wait=mocker.patch.object(item,"_await_employee_page",side_effect=[CmsAccessDeniedError("denied"),None])
    item.open_employee_page()
    assert wait.call_count == 2


def test_persistent_access_denied_stops_after_configured_attempts(configured,mocker):
    driver=Driver(); item=session(configured,driver)
    wait=mocker.patch.object(item,"_await_employee_page",side_effect=CmsAccessDeniedError("denied"))
    with pytest.raises(CmsAccessDeniedError): item.open_employee_page()
    assert wait.call_count == 3

def test_dom_confirm_save_clicks_yes(configured):
    driver=Driver(); message=Element("Confirm save?"); yes=Element("Yes",tag="button")
    message.mapping[SAVE_CONFIRM_YES]=[yes]; driver.mapping[SAVE_CONFIRM]=[message]
    session(configured,driver)._confirm_save()
    assert yes.clicked == 1


def test_confirm_save_requires_yes(configured):
    driver=Driver(); driver.mapping[SAVE_CONFIRM]=[]
    with pytest.raises(RuntimeError,match="Confirm save"):
        session(configured,driver)._confirm_save()
