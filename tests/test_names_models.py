import pytest

from worker_cms.models import CmsSite
from worker_cms.names import name_key, normalize_name, surname


@pytest.mark.parametrize(("raw", "expected"), [
    ("นายสมชาย ใจดี", "สมชาย ใจดี"), ("นาย สมชาย ใจดี", "สมชาย ใจดี"),
    ("นางสมหญิง ใจงาม", "สมหญิง ใจงาม"), ("นาง สมหญิง ใจงาม", "สมหญิง ใจงาม"),
    ("นางสาวสุพร ทดสอบ", "สุพร ทดสอบ"), ("นางสาว สุพร ทดสอบ", "สุพร ทดสอบ"),
    ("น.ส.อัญญา ดี", "อัญญา ดี"), ("น.ส. อัญญา ดี", "อัญญา ดี"),
    ("  นาย  ก ข  ", "ก ข"), ("ชื่อเดิม", "ชื่อเดิม"), (None, ""),
    ("นาย", ""), ("นางสาว", ""),
])
def test_normalize_name(raw, expected): assert normalize_name(raw) == expected


def test_name_key_is_unicode_and_case_insensitive(): assert name_key(" Test NAME ") == name_key("test name")
def test_surname_two_words(): assert surname("นายสมชาย ใจดี") == "ใจดี"
def test_surname_multiple_words(): assert surname("สมชาย กลาง ใจดี") == "กลาง ใจดี"
def test_surname_single_word(): assert surname("สมชาย") == ""
def test_sites():
    assert CmsSite.parse("sena").employee_url == "https://sena.on.lk/Common/Employee"
    assert CmsSite.parse("SENX").employee_url == "https://senx.on.lk/Common/Employee"
def test_invalid_site():
    with pytest.raises(ValueError, match="SENA หรือ SENX"): CmsSite.parse("other")

