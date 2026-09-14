from __future__ import annotations

import re
import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Callable
from urllib.parse import urljoin, urlparse

from selenium import webdriver
from selenium.common.exceptions import (
    ElementClickInterceptedException,
    NoAlertPresentException,
    StaleElementReferenceException,
    TimeoutException,
)
from selenium.webdriver.common.by import By
from selenium.webdriver.common.keys import Keys
from selenium.webdriver.support.ui import WebDriverWait

from .config import Config
from .models import CmsSite, WorkerRecord, WorkerResult, WorkerStatus
from .names import name_key, surname

USERNAME = (By.CSS_SELECTOR, "#txt_username")
PASSWORD = (By.CSS_SELECTOR, "#txt_password")
LOGIN = (By.CSS_SELECTOR, "#btn_login")
EMPLOYEE_SEARCH_INPUTS = (By.CSS_SELECTOR, "input[placeholder='Search'], input[placeholder='search']")
SEARCH_BUTTON_WITHIN = (By.XPATH, ".//*[self::button or self::a or @role='button'][contains(translate(@title,'SEARCH','search'),'search') or contains(translate(@aria-label,'SEARCH','search'),'search') or contains(concat(' ', normalize-space(@class), ' '), ' search ') or .//*[contains(concat(' ', normalize-space(@class), ' '), ' fa-search ') or contains(concat(' ', normalize-space(@class), ' '), ' glyphicon-search ') or contains(concat(' ', normalize-space(@class), ' '), ' icon-search ') or contains(concat(' ', normalize-space(@class), ' '), ' k-i-search ')]]")
GRID_ROWS = (By.CSS_SELECTOR, ".ngViewport .ngRow, .ng-viewport .ng-row, .ui-grid-viewport .ui-grid-row, .slick-viewport .slick-row, .k-grid-content tbody tr")
HTML_TABLE_ROWS = (By.XPATH, "//table[.//th[contains(translate(normalize-space(.),'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ'),'NAME')] and .//th[contains(translate(normalize-space(.),'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ'),'POSITION')] and .//th[contains(translate(normalize-space(.),'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ'),'USERNAME')]]//tbody/tr[td]")
RESULT_CELLS = (By.CSS_SELECTOR, "td, .ngCell, .ng-cell, .ui-grid-cell, .slick-cell")
STATUS_SWITCH_LABELS = (By.CSS_SELECTOR, "label.switch-label")
STATUS_ON = (By.XPATH, ".//*[contains(concat(' ', normalize-space(@class), ' '), ' toggle--on ') and normalize-space(.)='Employee']")
STATUS_OFF = (By.XPATH, ".//*[contains(concat(' ', normalize-space(@class), ' '), ' toggle--off ') and normalize-space(.)='Ex-Employee']")
STATUS_INPUT_NEAR_LABEL = (By.XPATH, "./preceding-sibling::input[contains(concat(' ', normalize-space(@class), ' '), ' switch-input ')][1] | .//input[contains(concat(' ', normalize-space(@class), ' '), ' switch-input ')]")
SAVE = (By.ID, "btn-save")
SAVE_CONFIRM = (By.XPATH, "//*[normalize-space(.)='Confirm save?' or normalize-space(.)='ยืนยันการบันทึก?']")
SAVE_CONFIRM_YES = (By.XPATH, ".//*[self::button or self::a or @role='button'][normalize-space(.)='Yes' or normalize-space(.)='ยืนยัน']")
SAVE_SUCCESS = (By.XPATH, "//*[normalize-space(.)='Save Data Successful' or normalize-space(.)='บันทึกข้อมูลสำเร็จ']")
SAVE_SUCCESS_OK = (By.XPATH, ".//*[self::button or self::a or @role='button'][normalize-space(.)='OK' or normalize-space(.)='ตกลง']")
EMPLOYEE_LIST_LINK = (By.XPATH, "//a[@href='/Common/Employee' and (translate(normalize-space(@title),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='employee list' or @gotolist)]")
SESSION_BUTTONS = [
    (By.XPATH, "//button[contains(text(),'Yes')]"), (By.XPATH, "//a[contains(text(),'Yes')]"),
    (By.CSS_SELECTOR, ".nx-confirm-button-ok, .swal2-confirm, #NotiflixConfirmButtonOk"),
]
NO_RESULTS = [(By.XPATH, "//*[contains(text(),'No data') or contains(text(),'ไม่พบข้อมูล') or contains(text(),'No results') or contains(text(),'No record') or contains(text(),'0 record') or contains(text(),'No matching records')]")]
USER_MENUS = [(By.CSS_SELECTOR, ".user-menu, .profile-dropdown, .avatar, .user-profile, .dropdown-toggle")]
LOGOUT = [(By.XPATH, "//a[contains(text(),'Logout')] | //button[contains(text(),'Logout')] | //a[contains(text(),'Sign Out')] | //a[contains(text(),'Log Out')] | //span[contains(text(),'Logout')]")]


class CmsAccessDeniedError(RuntimeError):
    pass


@dataclass(slots=True)
class SearchControl:
    input: object
    button: object


@dataclass(slots=True)
class SearchMatch:
    row: object
    name_cell: object


@dataclass(slots=True)
class StatusControl:
    input: object
    label: object


@dataclass(slots=True)
class DetailWindow:
    source_handle: str
    detail_handle: str


class SeleniumCmsAutomation:
    def __init__(self, config: Config, driver_factory=None):
        self.config = config
        self.driver_factory = driver_factory or self._create_driver

    def _create_driver(self):
        options = webdriver.ChromeOptions()
        if self.config.get_bool("HEADLESS", False):
            options.add_argument("--headless=new")
        for argument in ("--no-sandbox", "--disable-dev-shm-usage", "--window-size=1920,1080", "--disable-gpu", "--disable-extensions", "--disable-notifications", "--log-level=3"):
            options.add_argument(argument)
        options.add_experimental_option("excludeSwitches", ["enable-automation"])
        return webdriver.Chrome(options=options)

    def open(self, site: CmsSite, log: Callable[[str], None]) -> "SeleniumSession":
        log(f"CMS: {site.name} — {site.base_url}")
        session = SeleniumSession(self.driver_factory(), site, self.config, log)
        try:
            session.login()
            session.open_employee_page()
            return session
        except Exception:
            session.screenshot("startup_failed")
            session.close()
            raise


class SeleniumSession:
    def __init__(self, driver, site: CmsSite, config: Config, log: Callable[[str], None]):
        self.driver, self.site, self.config, self.log = driver, site, config, log
        self.timeout = max(1, config.get_int("WAIT_TIMEOUT", 10))
        self.search_settle = max(0.3, config.get_int("SEARCH_SETTLE_MILLIS", 750) / 1000)
        self.status_settle = max(0, config.get_int("STATUS_SETTLE_MILLIS", 1200) / 1000)
        try:
            self.driver.implicitly_wait(0)
        except AttributeError:
            pass

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def _wait(self, timeout: float | None = None):
        return WebDriverWait(self.driver, timeout or self.timeout, poll_frequency=0.2)

    def _first(self, selectors, clickable=False):
        for selector in selectors:
            for element in self.driver.find_elements(*selector):
                if not clickable or (element.is_displayed() and element.is_enabled()):
                    return element
        return None

    def _wait_any(self, selectors, clickable=False):
        if isinstance(selectors, tuple) and len(selectors) == 2 and isinstance(selectors[0], str):
            selectors = [selectors]
        return self._wait().until(lambda _: self._first(selectors, clickable))

    def _click(self, element):
        self.driver.execute_script("arguments[0].scrollIntoView({block:'center'});", element)
        try:
            element.click()
        except ElementClickInterceptedException:
            self.driver.execute_script("arguments[0].click();", element)

    def login(self):
        self.log("Step 1: Open URL and Login")
        self.driver.get(self.site.base_url)
        username, password = self._wait_any(USERNAME), self._wait_any(PASSWORD)
        username.clear(); username.send_keys(self.config.get("CMS_USERNAME"))
        password.clear(); password.send_keys(self.config.get("CMS_PASSWORD"))
        self._click(self._wait_any(LOGIN, True))

        def login_event(_):
            if self._first(SESSION_BUTTONS, True):
                return "session"
            return "success" if self._logged_in_ready() else False

        event = self._wait().until(login_event)
        if event == "session":
            self.log("Session conflict popup detected - clicking Yes")
            self._click(self._first(SESSION_BUTTONS, True))
            self._wait().until(lambda _: self._logged_in_ready())
        self.log(f"Login OK - URL: {self.driver.current_url}")

    def _logged_in_ready(self):
        url = self.driver.current_url or ""
        if self.driver.find_elements(*USERNAME) or "login" in url.lower():
            return False
        return self.driver.execute_script("return document.readyState") == "complete"

    def open_employee_page(self):
        self.log(f"Step 2: Open Employee page — {self.site.employee_url}")
        self.driver.get(self.site.employee_url)
        attempts = max(1, self.config.get_int("ACCESS_RETRY_COUNT", 3))
        for attempt in range(1, attempts + 1):
            try:
                self._await_employee_page()
                self.log(f"Employee page ready: {self.driver.current_url}")
                return
            except CmsAccessDeniedError:
                if attempt >= attempts:
                    raise
                self.log(f"CMS ตรวจสิทธิ์หน้า Employee ไม่ผ่านชั่วคราว โหลดหน้าเดิมซ้ำ (ครั้งที่ {attempt + 1}/{attempts})")
                self.driver.get(self.site.employee_url)

    def _await_employee_page(self):
        first_timeout = max(0.5, self.timeout / 3)
        try:
            self._wait_employee_ready(first_timeout)
        except TimeoutException as first:
            self._validate_location()
            self.log(f"หน้า Employee ยังไม่พร้อม เปิด URL ซ้ำ — URL ปัจจุบัน: {self.driver.current_url}")
            self.driver.get(self.site.employee_url)
            try:
                self._wait_employee_ready(max(0.5, self.timeout - first_timeout))
            except TimeoutException as second:
                current = self._validate_location()
                if self._is_employee_path(current):
                    count = len(self._employee_search_controls())
                    reason = f"พบชุดช่อง Employee Search และปุ่มแว่นขยายมากกว่าหนึ่งชุด ({count} ชุด)" if count > 1 else "ไม่พบช่อง Employee Search ที่จับคู่กับปุ่มแว่นขยาย"
                    raise RuntimeError(f"เปิดหน้า Employee แล้วแต่{reason} — URL: {self.driver.current_url}") from second
                raise RuntimeError(f"ระบบไม่ได้เปิดหน้า Employee ตาม URL ที่กำหนด — URL ปัจจุบัน: {self.driver.current_url}") from second

    def _wait_employee_ready(self, timeout=None):
        def ready(_):
            current = self._validate_location()
            return self._is_employee_path(current) and len(self._employee_search_controls()) == 1
        self._wait(timeout).until(ready)

    def _validate_location(self):
        self._reject_access_denied()
        current, expected = urlparse(self.driver.current_url), urlparse(self.site.employee_url)
        if not current.hostname:
            raise RuntimeError(f"CMS ส่ง URL ที่ไม่ถูกต้อง: {self.driver.current_url}")
        if current.hostname.casefold() != expected.hostname.casefold():
            raise RuntimeError(f"เปิดหน้า Employee ผิดระบบ CMS — URL ปัจจุบัน: {self.driver.current_url}")
        if "login" in current.path.casefold():
            raise RuntimeError(f"Session หมดอายุและถูกส่งกลับหน้า Login — URL: {self.driver.current_url}")
        return current

    def _reject_access_denied(self):
        source = self.driver.page_source or ""
        checks = ("you are not authorized", "คุณไม่ได้รับอนุญาตให้เห็นหน้านี้", "role is not defined")
        if not any(check in source.casefold() for check in checks):
            return
        match = re.search(r"Access\s*Id\s*[:：]\s*(\d+)", re.sub(r"<[^>]+>", " ", source), re.I)
        access = f" Access Id {match.group(1)}" if match else ""
        raise CmsAccessDeniedError(f"บัญชี CMS ไม่มีสิทธิ์เปิดหน้า Employee (Role is not defined{access}) กรุณาให้ผู้ดูแล CMS เพิ่มสิทธิ์แล้วลองใหม่")

    def _is_employee_path(self, parsed) -> bool:
        expected = urlparse(self.site.employee_url).path.rstrip("/").casefold()
        actual = (parsed.path or "").rstrip("/").casefold()
        return actual == expected or actual.startswith(expected + "/")

    def _adjacent_buttons(self, input_element):
        script = """
        const input=arguments[0],ir=input.getBoundingClientRect();
        const visible=e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden'&&!e.disabled};
        const c=[...document.querySelectorAll('button,a,[role="button"],input[type="button"],input[type="submit"],.input-group-addon')].filter(e=>{if(!visible(e)||e===input)return false;const r=e.getBoundingClientRect(),v=Math.abs((r.top+r.height/2)-(ir.top+ir.height/2));return v<=Math.max(12,ir.height*.65)&&r.left>=ir.right-8&&r.left<=ir.right+110&&r.width<=120&&r.height<=Math.max(80,ir.height*2)}).sort((a,b)=>Math.abs(a.getBoundingClientRect().left-ir.right)-Math.abs(b.getBoundingClientRect().left-ir.right));
        return c.length?[c[0]]:[];
        """
        try:
            return [item for item in self.driver.execute_script(script, input_element) or [] if item.is_displayed() and item.is_enabled()]
        except Exception:
            return []

    def _employee_search_controls(self):
        controls = []
        for input_element in self.driver.find_elements(*EMPLOYEE_SEARCH_INPUTS):
            if not input_element.is_displayed() or not input_element.is_enabled() or (input_element.get_attribute("placeholder") or "").strip().casefold() != "search":
                continue
            adjacent = self._adjacent_buttons(input_element)
            if adjacent:
                controls.append(SearchControl(input_element, adjacent[0])); continue
            container = input_element
            for level in range(4):
                if level:
                    try: container = container.find_element(By.XPATH, "..")
                    except Exception: break
                buttons = [item for item in container.find_elements(*SEARCH_BUTTON_WITHIN) if item.is_displayed() and item.is_enabled()]
                if buttons:
                    controls.extend(SearchControl(input_element, button) for button in buttons); break
        return controls

    def _wait_search_control(self):
        try:
            return self._wait().until(lambda _: (items[0] if len(items := self._employee_search_controls()) == 1 else False))
        except TimeoutException as exc:
            count = len(self._employee_search_controls())
            message = "ไม่พบช่อง Employee Search ที่จับคู่กับปุ่มแว่นขยาย" if count == 0 else f"พบชุดช่อง Employee Search และปุ่มแว่นขยายมากกว่าหนึ่งชุด ({count} ชุด)"
            raise RuntimeError(message) from exc

    def process(self, record: WorkerRecord, index: int, total: int, dry_run: bool) -> WorkerResult:
        self.log(f"[{index}/{total}] Row {record.row} | ID: {record.id} | Search: {record.search_name}")
        try:
            matches = self._search(record.search_name, record.search_name)
            if len(matches) > 1:
                raise RuntimeError(f"ต้องพบแถวที่มีชื่อตรงกับ '{record.search_name}' เพียง 1 แถว (พบ {len(matches)} แถว)")
            last_name = surname(record.search_name)
            if not matches and last_name:
                self.log(f"ไม่พบจากชื่อ ลองค้นหาด้วยนามสกุล: {last_name}")
                matches = self._search(last_name, record.search_name)
                if len(matches) > 1:
                    raise RuntimeError(f"ค้นหาด้วยนามสกุลแล้วพบชื่อ '{record.search_name}' ซ้ำ {len(matches)} แถว")
            if not matches:
                message = "ไม่พบ (ชื่อไม่มีนามสกุลสำหรับค้นหาซ้ำ)" if not last_name else "ไม่พบ (ค้นหาด้วยชื่อและนามสกุลแล้ว)"
                self.log(message); return self._result(record, WorkerStatus.NOT_FOUND, message)
            if dry_run:
                message = "พบข้อมูล — ผลค้นหาหนึ่งรายการ"
                self.log(message); return self._result(record, WorkerStatus.SKIPPED, message)
            links = self._detail_links(matches[0].row)
            if len(links) != 1:
                raise RuntimeError(f"พบข้อมูลหนึ่งแถวสำหรับ '{record.search_name}' แต่ต้องพบลิงก์ Employee Detail เพียงหนึ่งจุด (พบ {len(links)} จุด)")
            detail = self._open_detail(links[0])
            message = self._deactivate()
            self._return_to_list(detail)
            return self._result(record, WorkerStatus.SUCCESS, message)
        except Exception as exc:
            prefix = "Timeout" if isinstance(exc, TimeoutException) else "Error"
            message = f"{prefix}: {str(exc)[:160] or type(exc).__name__}"
            self.log(message); self.screenshot(record.code); self._recover_list()
            return self._result(record, WorkerStatus.FAILED, message)

    def _search(self, term: str, target_name: str):
        previous = self._row_signature(self._visible_rows())
        control = self._wait_search_control()
        control.input.clear(); control.input.send_keys(term)
        self.log(f"Employee Search control: {self._describe(control.button)}")
        self._click(control.button)
        self.log(f"Employee Search: {term} (submit: ปุ่มแว่นขยาย)")
        primary = min(self.timeout, max(2.0, self.search_settle * 2))
        try:
            self._wait_search_result(previous, target_name, primary)
        except TimeoutException:
            refreshed = self._visible_rows()
            if not previous and not refreshed:
                self.log(f"Employee grid remained empty after {int(primary * 1000)} ms")
            else:
                self.log("ปุ่มแว่นขยายยังไม่ทำให้ตารางเปลี่ยน กำลังส่งคำค้นซ้ำด้วย Enter")
                self._click(control.input); control.input.clear(); control.input.send_keys(term); control.input.send_keys(Keys.RETURN)
                try:
                    self._wait_search_result(previous, target_name, max(0.3, self.timeout - primary))
                    self.log(f"Employee Search: {term} (submit fallback: Enter)")
                except TimeoutException as exc:
                    refreshed = self._visible_rows()
                    if previous and self._row_signature(refreshed) == previous:
                        raise RuntimeError(f"ตารางผลค้นหาไม่อัปเดตหลังส่งทั้งปุ่มแว่นขยายและ Enter สำหรับ '{term}' ภายใน {self.timeout} วินาที") from exc
        rows, matches = self._read_search_grid(target_name)
        if len(rows) == 1:
            self.log("Employee grid rows: 1 — ถือว่าพบข้อมูลจากผลลัพธ์หนึ่งแถว")
        else:
            self.log(f"Employee grid rows: {len(rows)}")
        if not rows:
            self.log(f"CMS ไม่คืนแถวผลลัพธ์สำหรับคำค้น: {term}")
        elif len(rows) > 1 and not matches:
            samples = " | ".join(filter(None, (self._safe_row_text(row) for row in rows[:3])))
            self.log(f"CMS คืน {len(rows)} แถว แต่ไม่มีชื่อเต็มตรงกับ '{target_name}'" + (f" — ตัวอย่าง: {samples}" if samples else ""))
            self.screenshot("search_mismatch_" + term)
        return matches

    def _wait_search_result(self, previous, target_name, timeout):
        target, started = name_key(target_name), time.monotonic()
        def ready(_):
            try:
                rows = self._visible_rows()
                if any(self._first([selector]) and self._first([selector]).is_displayed() for selector in NO_RESULTS):
                    return rows
                current = self._row_signature(rows)
                if any(name_key(cell.text) == target for row in rows for cell in self._result_cells(row)):
                    return rows
                if current == previous:
                    return False
                if rows:
                    return rows
                return rows if time.monotonic() - started >= self.search_settle * 2 else False
            except StaleElementReferenceException:
                return False
        return self._wait(timeout).until(ready)

    def _read_search_grid(self, target_name):
        target = name_key(target_name)
        def read(_):
            try:
                rows, matches = self._visible_rows(), []
                for row in rows:
                    for cell in self._result_cells(row):
                        if name_key(cell.text) == target:
                            matches.append(SearchMatch(row, cell)); break
                if len(rows) == 1 and not matches:
                    matches.append(SearchMatch(rows[0], self._result_cells(rows[0])[0]))
                return rows, matches
            except StaleElementReferenceException:
                return False
        return self._wait(max(0.5, min(2, self.timeout))).until(read)

    def _visible_rows(self):
        rows = []
        for selector in (GRID_ROWS, HTML_TABLE_ROWS):
            for row in self.driver.find_elements(*selector):
                if row.is_displayed() and self._result_cells(row) and row not in rows:
                    rows.append(row)
        return rows

    @staticmethod
    def _result_cells(row):
        return [cell for cell in row.find_elements(*RESULT_CELLS) if cell.is_displayed()]

    @staticmethod
    def _row_signature(rows):
        return [" ".join(row.text.split()) for row in rows]

    @staticmethod
    def _safe_row_text(row):
        try: return " ".join(row.text.split())
        except StaleElementReferenceException: return ""

    def _detail_links(self, row):
        links = []
        for cell in self._result_cells(row):
            for link in cell.find_elements(By.CSS_SELECTOR, "a[href]"):
                href = (link.get_attribute("href") or "").strip()
                target = urlparse(urljoin(self.site.employee_url, href))
                if link.is_displayed() and link.is_enabled() and self._is_detail_path(target) and link not in links:
                    links.append(link)
        return links

    @staticmethod
    def _describe(element):
        css = ".".join((element.get_attribute("class") or "").split())
        title = (element.get_attribute("title") or "").strip()
        return f"<{element.tag_name}{' class=' + css if css else ''}{' title=' + title if title else ''}>"

    def _open_detail(self, link):
        source, before = self.driver.current_window_handle, set(self.driver.window_handles)
        self._click(link)
        def opened(_):
            new = set(self.driver.window_handles) - before
            if len(new) > 1: raise RuntimeError("ลิงก์ Name เปิดหน้ารายละเอียดมากกว่าหนึ่งแท็บ")
            if len(new) == 1: return next(iter(new))
            return self.driver.current_window_handle if self._is_detail_path(urlparse(self.driver.current_url)) else False
        detail = self._wait().until(opened)
        if detail != self.driver.current_window_handle:
            self.driver.switch_to.window(detail)
        self._wait_detail(); self._wait_status_control()
        self.log(f"Employee detail ready: {self.driver.current_url}")
        return DetailWindow(source, detail)

    def _is_detail_path(self, parsed):
        expected = urlparse(self.site.employee_url)
        form = expected.path.rstrip("/") + "/Form"
        actual = (parsed.path or "").rstrip("/")
        return (parsed.hostname or "").casefold() == expected.hostname.casefold() and (actual.casefold() == form.casefold() or actual.casefold().startswith(form.casefold() + "/"))

    def _wait_detail(self):
        def ready(_):
            url = self.driver.current_url or ""
            if not url or url.startswith("about:"): return False
            parsed, expected = urlparse(url), urlparse(self.site.employee_url)
            if (parsed.hostname or "").casefold() != expected.hostname.casefold(): raise RuntimeError(f"หน้า Employee Detail เปิดผิดระบบ CMS — URL: {url}")
            if "login" in parsed.path.casefold(): raise RuntimeError(f"Session หมดอายุและถูกส่งกลับหน้า Login — URL: {url}")
            return self._is_detail_path(parsed)
        try: self._wait().until(ready)
        except TimeoutException as exc: raise RuntimeError(f"คลิก Name แล้วแต่ไม่พบหน้า Employee Detailภายใน {self.timeout} วินาที — URL: {self.driver.current_url}") from exc

    def _status_controls(self):
        controls = []
        for label in self.driver.find_elements(*STATUS_SWITCH_LABELS):
            if not label.is_displayed() or len(label.find_elements(*STATUS_ON)) != 1 or len(label.find_elements(*STATUS_OFF)) != 1: continue
            inputs = []
            for_id = (label.get_attribute("for") or "").strip()
            if for_id:
                inputs.extend(self.driver.find_elements(By.ID, for_id))
            inputs.extend(label.find_elements(*STATUS_INPUT_NEAR_LABEL))
            for input_element in inputs:
                if "switch-input" in (input_element.get_attribute("class") or "").split() and not any(c.input == input_element and c.label == label for c in controls):
                    controls.append(StatusControl(input_element, label))
        return controls

    def _wait_status_control(self):
        def one(_):
            items = self._status_controls()
            if len(items) > 1: raise RuntimeError(f"พบสวิตช์ Status Employee / Ex-Employee มากกว่าหนึ่งจุด ({len(items)} จุด)")
            return items[0] if len(items) == 1 else False
        try: return self._wait().until(one)
        except TimeoutException as exc: raise RuntimeError("ไม่พบ input.switch-input ที่ผูกกับ Status Employee / Ex-Employee ในหน้า Employee Detail") from exc

    def _save_button(self):
        if not self._is_detail_path(urlparse(self.driver.current_url)):
            raise RuntimeError(f"ไม่สามารถค้นหาปุ่ม #btn-save นอกหน้า Employee Detail — URL: {self.driver.current_url}")
        buttons = [item for item in self.driver.find_elements(*SAVE) if item.is_displayed() and item.is_enabled()]
        if len(buttons) != 1:
            raise RuntimeError(f"ต้องพบปุ่มบันทึก id='btn-save' ที่มองเห็นและพร้อมใช้งานเพียงหนึ่งปุ่ม (พบ {len(buttons)} ปุ่ม)")
        return buttons[0]

    @staticmethod
    def _ex_text_visible(control):
        return " ".join(control.label.text.split()) == "Ex-Employee" or any(item.is_displayed() for item in control.label.find_elements(*STATUS_OFF))

    def _deactivate(self):
        if self.status_settle: time.sleep(self.status_settle)
        control = self._wait_status_control()
        current = "Employee" if control.input.is_selected() else "Ex-Employee"
        self.log(f"Status after Employee Detail settled: {current}")
        if current == "Ex-Employee":
            self.log("Status เป็น Ex-Employee อยู่แล้ว")
            return "Ex-Employee — เป็นสถานะนี้อยู่แล้ว"
        if not control.input.is_enabled() or not control.label.is_enabled(): raise RuntimeError("สวิตช์ Status ไม่พร้อมใช้งาน")
        self._save_button()
        self._click(control.label)
        def changed(_):
            items = self._status_controls()
            if len(items) > 1: raise RuntimeError(f"พบสวิตช์ Status Employee / Ex-Employee มากกว่าหนึ่งจุด ({len(items)} จุด)")
            return len(items) == 1 and not items[0].input.is_selected() and self._ex_text_visible(items[0])
        self._wait().until(changed)
        self.log("Status changed: .switch-input:checked → unchecked (Ex-Employee)")
        self._click(self._save_button())
        self.log("Save Document clicked: #btn-save")
        self._confirm_save()
        self._close_save_success()
        self.log("Save Data Successful — ปิดหน้าต่างด้วย OK แล้ว")
        return "Ex-Employee — บันทึกสำเร็จ"

    def _confirm_save(self):
        def confirm(_):
            try:
                alert = self.driver.switch_to.alert
                text = alert.text.strip()
                if "confirm save" in text.casefold() or "ยืนยันการบันทึก" in text:
                    alert.accept()
                    return True
            except (NoAlertPresentException, AttributeError):
                pass
            for message in self.driver.find_elements(*SAVE_CONFIRM):
                if not message.is_displayed():
                    continue
                container = message
                for _ in range(6):
                    buttons = [item for item in container.find_elements(*SAVE_CONFIRM_YES) if item.is_displayed() and item.is_enabled()]
                    if len(buttons) == 1:
                        self._click(buttons[0])
                        return True
                    if len(buttons) > 1:
                        raise RuntimeError(f"พบปุ่ม Yes ในหน้าต่าง Confirm save มากกว่าหนึ่งปุ่ม ({len(buttons)} ปุ่ม)")
                    try:
                        container = container.find_element(By.XPATH, "..")
                    except Exception:
                        break
            return False
        try:
            self._wait().until(confirm)
        except TimeoutException as exc:
            raise RuntimeError("คลิก Save Document แล้วแต่ไม่พบหน้าต่าง Confirm save หรือปุ่ม Yes") from exc
        self.log("Confirm save — clicked Yes")

    def _close_save_success(self):
        def close(_):
            try:
                alert = self.driver.switch_to.alert
                if alert.text.strip().casefold() == "save data successful": alert.accept(); return True
            except NoAlertPresentException: pass
            for message in self.driver.find_elements(*SAVE_SUCCESS):
                if not message.is_displayed(): continue
                container = message
                for _ in range(6):
                    buttons = [item for item in container.find_elements(*SAVE_SUCCESS_OK) if item.is_displayed() and item.is_enabled()]
                    if len(buttons) == 1: self._click(buttons[0]); return True
                    try: container = container.find_element(By.XPATH, "..")
                    except Exception: break
            return False
        self._wait().until(close)

    def _return_to_list(self, detail):
        def one(_):
            links = [item for item in self.driver.find_elements(*EMPLOYEE_LIST_LINK) if item.is_displayed() and item.is_enabled()]
            if len(links) > 1: raise RuntimeError("พบลิงก์กลับ Employee List มากกว่าหนึ่งจุด")
            return links[0] if len(links) == 1 else False
        self._click(self._wait().until(one)); self._wait_employee_ready()
        current = self.driver.current_window_handle
        if detail.source_handle != current and detail.source_handle in self.driver.window_handles:
            self.driver.switch_to.window(detail.source_handle); self.driver.close(); self.driver.switch_to.window(current)
        self.log("กลับหน้า Employee List แล้ว")

    def _recover_list(self):
        try:
            if not self._employee_search_controls(): self.driver.get(self.site.employee_url)
            self._wait_employee_ready()
            keep = self.driver.current_window_handle
            for handle in list(self.driver.window_handles):
                if handle == keep: continue
                self.driver.switch_to.window(handle); self.driver.close()
            self.driver.switch_to.window(keep)
        except Exception: pass

    @staticmethod
    def _result(record, status, message):
        return WorkerResult(record.row, record.code, record.name, record.search_name, status, message)

    def screenshot(self, code):
        try:
            directory = Path("logs/screenshots"); directory.mkdir(parents=True, exist_ok=True)
            safe = re.sub(r"[^\w.-]+", "_", str(code))
            self.driver.save_screenshot(str(directory / f"error_{safe}_{datetime.now():%Y%m%d_%H%M%S}.png"))
        except Exception: pass

    def logout(self):
        try:
            menu = self._first(USER_MENUS, True)
            if menu: self._click(menu)
            self._click(self._wait_any(LOGOUT, True))
            self._wait().until(lambda _: self.driver.find_elements(*USERNAME) or "login" in self.driver.current_url.casefold())
            self.log("Logout สำเร็จ")
        except Exception:
            self.log("ไม่พบปุ่ม Logout - ปิด browser โดยตรง")

    def close(self):
        try: self.driver.quit()
        except Exception: pass
