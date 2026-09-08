package th.co.sena.workercms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ExcelServiceTest {
    private final ExcelService service = new ExcelService(new NameService());

    @Test
    void readsReorderedHeadersDatesAndLeadingZeros() throws Exception {
        byte[] input;
        try (Workbook book = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("Data");
            Row header = sheet.createRow(0);
            String[] names = {"ชื่อ", "No", "แผนก", "วันที่พ้นสภาพ", "ID", "ตำแหน่ง", "E-mail"};
            for (int i = 0; i < names.length; i++) header.createCell(i).setCellValue(names[i]);
            Row row = sheet.createRow(1); row.createCell(0).setCellValue("นาย สมชาย ใจดี");
            row.createCell(1).setCellValue(7); row.getCell(1).setCellStyle(book.createCellStyle()); row.getCell(1).getCellStyle().setDataFormat(book.createDataFormat().getFormat("0000"));
            row.createCell(2).setCellValue("ฝ่ายขาย"); row.createCell(3).setCellValue(LocalDate.of(2026, 4, 26));
            row.getCell(3).setCellStyle(book.createCellStyle()); row.getCell(3).getCellStyle().setDataFormat(book.createDataFormat().getFormat("dd/mm/yyyy"));
            row.createCell(4).setCellValue("00123"); row.createCell(5).setCellValue("หัวหน้า"); row.createCell(6).setCellValue("a@example.com");
            book.write(output); input = output.toByteArray();
        }
        WorkerRecord record = service.parse(input, 1, "Data").getFirst();
        assertEquals("สมชาย ใจดี", record.searchName()); assertEquals("26/04/2026", record.terminationDate());
        assertEquals("00123", record.id()); assertEquals("0007", record.no());
    }

    @Test
    void rejectsNamesThatCollideAfterPrefixRemoval() throws Exception {
        byte[] input = workbookWithNames("นายสมชาย ใจดี", "นางสมชาย ใจดี");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.parse(input, 1, "")).getMessage().contains("แถว 2 และ 3"));
    }

    @Test
    void exportsTypedDateTextIdentifiersAndStatuses() throws Exception {
        WorkerRecord record = new WorkerRecord(2, "=2+2", "สมชาย ใจดี", "26/04/2026", "=2+2", "0007", "นาย สมชาย ใจดี", "a@example.com", "หัวหน้า", "ฝ่ายขาย");
        byte[] raw = service.export(List.of(record), List.of(new WorkerResult(2, record.code(), record.name(), record.searchName(), WorkerStatus.SUCCESS, "ok")));
        try (Workbook book = new XSSFWorkbook(new ByteArrayInputStream(raw))) {
            var sheet = book.getSheet("Workers");
            assertEquals("A2", sheet.getPaneInformation() == null ? "" : "A2");
            assertEquals("พบข้อมูล", sheet.getRow(1).getCell(7).getStringCellValue());
            assertEquals(CellType.STRING, sheet.getRow(1).getCell(1).getCellType());
            assertEquals("=2+2", sheet.getRow(1).getCell(1).getStringCellValue());
            assertTrue(org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(sheet.getRow(1).getCell(0)));
            assertEquals("A1:H2", ((org.apache.poi.xssf.usermodel.XSSFSheet) sheet).getCTWorksheet().getAutoFilter().getRef());
        }
    }

    private byte[] workbookWithNames(String first, String second) throws Exception {
        try (Workbook book = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = book.createSheet(); Row header = sheet.createRow(0);
            String[] labels = {"วันที่พ้นสภาพ", "ID", "No", "ชื่อ", "E-mail", "ตำแหน่ง", "แผนก"};
            for (int i = 0; i < labels.length; i++) header.createCell(i).setCellValue(labels[i]);
            for (int i = 0; i < 2; i++) { Row row = sheet.createRow(i + 1); row.createCell(1).setCellValue("ID" + i); row.createCell(3).setCellValue(i == 0 ? first : second); }
            book.write(output); return output.toByteArray();
        }
    }
}
