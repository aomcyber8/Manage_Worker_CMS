package th.co.sena.workercms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.time.YearMonth;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResignHistoryServiceTest {
    private final NameService names = new NameService();

    @Test void parsesHistoryTableAndPageCount() {
        var service = new ResignHistoryService(names, page -> "");
        var parsed = service.parsePage(page("""
            <table id="data">
              <tr><th colspan="9">ประวัติผู้ที่ลาออกแล้ว</th></tr>
              <tr><th>วันที่พ้นสภาพ</th><th>ID</th><th>No</th><th>ชื่อ</th><th>E-mail</th><th>ตำแหน่ง</th><th>แผนก</th></tr>
              <tr><td><b>01/09/2026</b></td><td>BK001</td><td>0007</td><td>นาย  สมชาย   ใจดี</td><td>a@sena.co.th</td><td>ผู้จัดการ</td><td>ฝ่ายขาย</td><td></td></tr>
              <tr><td colspan="9"><select name="page"><option>1</option><option>2</option><option>102</option></select></td></tr>
            </table>
            """));
        assertEquals(102, parsed.totalPages());
        assertEquals(1, parsed.rows().size());
        assertEquals("BK001", parsed.rows().getFirst().id());
        assertEquals("นาย สมชาย ใจดี", parsed.rows().getFirst().name());
    }

    @Test void loadsOnlySelectedMonthAndStopsWhenOlderRowsAppear() {
        Map<Integer, String> pages = Map.of(
            1, page("""
                <table id="data">
                  <tr><td>08/09/2026</td><td>SEP1</td><td>1</td><td>นาย ก กันยายน</td><td></td><td></td><td></td></tr>
                  <tr><td colspan="9"><select name="page"><option>1</option><option>2</option><option>3</option></select></td></tr>
                </table>
                """),
            2, page("""
                <table id="data">
                  <tr><td>31/08/2026</td><td>AUG1</td><td>0008</td><td>น.ส. อัญญา ใจดี</td><td>a@sena.co.th</td><td>เจ้าหน้าที่</td><td>บุคคล</td></tr>
                  <tr><td>01/08/2026</td><td>AUG2</td><td>0009</td><td>นาย บรรจง รักงาน</td><td>b@sena.co.th</td><td>หัวหน้า</td><td>ขาย</td></tr>
                  <tr><td>31/07/2026</td><td>JUL1</td><td>10</td><td>นาย ค คนเก่า</td><td></td><td></td><td></td></tr>
                </table>
                """),
            3, page("<table id=\"data\"><tr><td>01/07/2026</td><td>NEVER</td><td>1</td><td>นาย ง ไม่ควรถูกอ่าน</td><td></td><td></td><td></td></tr></table>")
        );
        var service = new ResignHistoryService(names, pages::get);
        var records = service.load(YearMonth.of(2026, 8));
        assertEquals(2, records.size());
        assertEquals("AUG1", records.getFirst().id());
        assertEquals("อัญญา ใจดี", records.getFirst().searchName());
        assertEquals("31/08/2026", records.getFirst().terminationDate());
        assertEquals("0008", records.getFirst().no());
    }

    @Test void reportsWhenMonthHasNoRecords() {
        var service = new ResignHistoryService(names, pageNumber -> page("""
            <table id="data">
              <tr><td>01/07/2026</td><td>JUL1</td><td>1</td><td>นาย คน เดือนเจ็ด</td><td></td><td></td><td></td></tr>
            </table>
            """));
        var error = assertThrows(IllegalArgumentException.class,
            () -> service.load(YearMonth.of(2026, 8)));
        assertEquals("ไม่พบข้อมูลผู้ลาออกในเดือน 2026-08", error.getMessage());
    }

    @Test void rejectsDuplicateNormalizedNames() {
        var service = new ResignHistoryService(names, pageNumber -> page("""
            <table id="data">
              <tr><td>01/08/2026</td><td>A1</td><td>1</td><td>นาย สมชาย ใจดี</td><td></td><td></td><td></td></tr>
              <tr><td>02/08/2026</td><td>A2</td><td>2</td><td>นายสมชาย ใจดี</td><td></td><td></td><td></td></tr>
            </table>
            """));
        assertThrows(IllegalArgumentException.class, () -> service.load(YearMonth.of(2026, 8)));
    }

    private static String page(String body) {
        return "<html><head><meta charset=\"utf-8\"></head><body>" + body + "</body></html>";
    }
}

