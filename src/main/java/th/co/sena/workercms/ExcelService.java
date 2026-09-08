package th.co.sena.workercms;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

@Service
public class ExcelService {
    public static final int MAX_UPLOAD = 5 * 1024 * 1024;
    public static final List<String> EXPORT_HEADERS = List.of(
        "วันที่พ้นสภาพ", "ID", "No", "ชื่อ", "E-mail", "ตำแหน่ง", "แผนก", "ผลการค้นหา"
    );
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Pattern EXCEL_DATE = Pattern.compile("\\d{2}/\\d{2}/\\d{4}");
    private static final Map<String, String> FIELDS = new LinkedHashMap<>();
    private static final Map<String, String> LABELS = new LinkedHashMap<>();
    static {
        FIELDS.put("วันที่พ้นสภาพ", "termination_date"); LABELS.put("วันที่พ้นสภาพ", "วันที่พ้นสภาพ");
        FIELDS.put("id", "id"); LABELS.put("id", "ID");
        FIELDS.put("no", "no"); LABELS.put("no", "No");
        FIELDS.put("ชื่อ", "name"); LABELS.put("ชื่อ", "ชื่อ");
        FIELDS.put("e-mail", "email"); LABELS.put("e-mail", "E-mail");
        FIELDS.put("ตำแหน่ง", "position"); LABELS.put("ตำแหน่ง", "ตำแหน่ง");
        FIELDS.put("แผนก", "department"); LABELS.put("แผนก", "แผนก");
    }

    private final NameService names;
    public ExcelService(NameService names) { this.names = names; }

    public List<WorkerRecord> parse(byte[] raw, int headerRow, String sheetName) {
        if (raw == null || raw.length == 0 || raw.length > MAX_UPLOAD) {
            throw new IllegalArgumentException("ไฟล์ต้องมีขนาดไม่เกิน 5 MB");
        }
        if (headerRow < 1 || headerRow > 1_048_576) throw new IllegalArgumentException("แถวหัวตารางไม่ถูกต้อง");
        validateArchive(raw);
        try (Workbook book = WorkbookFactory.create(new ByteArrayInputStream(raw))) {
            Sheet sheet;
            if (sheetName != null && !sheetName.isBlank()) {
                sheet = book.getSheet(sheetName);
                if (sheet == null) throw new IllegalArgumentException("ไม่พบชื่อชีตที่ระบุ");
            } else sheet = book.getSheetAt(book.getActiveSheetIndex());
            if (sheet.getLastRowNum() + 1 > 50_000) throw new IllegalArgumentException("รองรับ Excel ไม่เกิน 50,000 แถว");

            DataFormatter formatter = new DataFormatter(Locale.US);
            Row header = sheet.getRow(headerRow - 1);
            if (header == null) throw new IllegalArgumentException("ไม่พบหัวตาราง: " + String.join(", ", EXPORT_HEADERS.subList(0, 7)));
            Map<String, Integer> found = new HashMap<>();
            List<String> duplicates = new ArrayList<>();
            for (Cell cell : header) {
                String normalized = normalizeHeader(formatter.formatCellValue(cell));
                if (!FIELDS.containsKey(normalized)) continue;
                if (found.putIfAbsent(normalized, cell.getColumnIndex()) != null) duplicates.add(cell.getStringCellValue().strip());
            }
            if (!duplicates.isEmpty()) throw new IllegalArgumentException("พบหัวตารางซ้ำ: " + String.join(", ", duplicates));
            List<String> missing = FIELDS.keySet().stream().filter(key -> !found.containsKey(key)).map(LABELS::get).toList();
            if (!missing.isEmpty()) throw new IllegalArgumentException("ไม่พบหัวตาราง: " + String.join(", ", missing));

            List<WorkerRecord> records = new ArrayList<>();
            Map<String, Integer> seenNames = new HashMap<>();
            for (int index = headerRow; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                Map<String, String> values = new HashMap<>();
                for (Map.Entry<String, String> field : FIELDS.entrySet()) {
                    Cell cell = row == null ? null : row.getCell(found.get(field.getKey()), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    values.put(field.getValue(), display(cell, formatter));
                }
                if (values.values().stream().allMatch(String::isBlank)) continue;
                int excelRow = index + 1;
                List<String> empty = new ArrayList<>();
                if (values.get("id").isBlank()) empty.add("ID");
                if (values.get("name").isBlank()) empty.add("ชื่อ");
                if (!empty.isEmpty()) throw new IllegalArgumentException("แถว " + excelRow + " ไม่มีข้อมูล: " + String.join(", ", empty));
                String searchName = names.normalize(values.get("name"));
                if (searchName.isBlank()) throw new IllegalArgumentException("แถว " + excelRow + " ไม่มีชื่อสำหรับค้นหา");
                String nameKey = names.key(searchName);
                Integer previous = seenNames.putIfAbsent(nameKey, excelRow);
                if (previous != null) throw new IllegalArgumentException(
                    "ชื่อซ้ำหลังตัดคำนำหน้า: " + searchName + " (แถว " + previous + " และ " + excelRow + ")"
                );
                records.add(new WorkerRecord(excelRow, values.get("id"), searchName,
                    values.get("termination_date"), values.get("id"), values.get("no"), values.get("name"),
                    values.get("email"), values.get("position"), values.get("department")));
                if (records.size() > 5_000) throw new IllegalArgumentException("รองรับไม่เกิน 5,000 รายการต่อครั้ง");
            }
            if (records.isEmpty()) throw new IllegalArgumentException("ไม่พบข้อมูล Worker หลังแถวหัวตาราง");
            return List.copyOf(records);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("อ่านข้อมูลไม่ได้ กรุณาตรวจสอบว่าเป็นไฟล์ Excel ที่สมบูรณ์", exception);
        }
    }

    private static String normalizeHeader(String value) { return value.strip().toLowerCase(Locale.ROOT); }
    private static void validateArchive(byte[] raw) {
        long total = 0; int entries = 0; byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(raw))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 10_000) throw new IllegalArgumentException("ข้อมูลใน Excel มีจำนวนไฟล์ภายในมากเกินไป");
                int read;
                while ((read = zip.read(buffer)) >= 0) {
                    total += read;
                    if (total > 40L * 1024 * 1024) throw new IllegalArgumentException("ข้อมูลใน Excel มีขนาดใหญ่เกินไป");
                }
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new IllegalArgumentException("ไฟล์ Excel ไม่สมบูรณ์", exception);
        }
    }
    private static String display(Cell cell, DataFormatter formatter) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.NUMERIC && org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate().format(DATE);
        }
        return formatter.formatCellValue(cell).strip();
    }

    public byte[] export(List<WorkerRecord> records, List<WorkerResult> results) {
        if (records == null || records.isEmpty()) throw new IllegalArgumentException("ไม่มีข้อมูลสำหรับ Export กรุณาอ่านไฟล์ Excel ก่อน");
        Map<Integer, WorkerResult> byRow = new HashMap<>();
        if (results != null) results.forEach(result -> byRow.put(result.row(), result));
        try (Workbook book = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("Workers");
            CellStyle header = book.createCellStyle();
            header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex()); header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font font = book.createFont(); font.setBold(true); font.setColor(IndexedColors.WHITE.getIndex()); header.setFont(font);
            Row heading = sheet.createRow(0);
            for (int column = 0; column < EXPORT_HEADERS.size(); column++) {
                Cell cell = heading.createCell(column, CellType.STRING); cell.setCellValue(EXPORT_HEADERS.get(column)); cell.setCellStyle(header);
            }
            CellStyle dateStyle = book.createCellStyle(); dateStyle.setDataFormat(book.createDataFormat().getFormat("dd/mm/yyyy"));
            Map<String, CellStyle> statusStyles = statusStyles(book);
            for (int index = 0; index < records.size(); index++) {
                WorkerRecord record = records.get(index); Row row = sheet.createRow(index + 1);
                setDateOrText(row.createCell(0), record.terminationDate(), dateStyle);
                setText(row, 1, record.id()); setText(row, 2, record.no()); setText(row, 3, record.name());
                setText(row, 4, record.email()); setText(row, 5, record.position()); setText(row, 6, record.department());
                String status = resultLabel(byRow.get(record.row()));
                Cell statusCell = row.createCell(7, CellType.STRING); statusCell.setCellValue(status); statusCell.setCellStyle(statusStyles.get(status));
            }
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new CellRangeAddress(0, records.size(), 0, 7));
            int[] widths = {16, 18, 14, 30, 34, 32, 32, 18};
            for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
            book.write(output); return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("สร้างไฟล์ Excel ไม่สำเร็จ", exception);
        }
    }

    private static void setText(Row row, int column, String value) {
        Cell cell = row.createCell(column, CellType.STRING); cell.setCellValue(value == null ? "" : value);
    }
    private static void setDateOrText(Cell cell, String value, CellStyle style) {
        if (value != null && EXCEL_DATE.matcher(value).matches()) {
            cell.setCellValue(LocalDate.parse(value, DATE)); cell.setCellStyle(style);
        } else cell.setCellValue(value == null ? "" : value);
    }
    private static String resultLabel(WorkerResult result) {
        if (result == null) return "รอดำเนินการ";
        return switch (result.status()) {
            case SUCCESS, SKIPPED -> "พบข้อมูล";
            case NOT_FOUND -> "หาไม่พบ";
            case FAILED -> "ผิดพลาด";
        };
    }
    private static Map<String, CellStyle> statusStyles(Workbook book) {
        Map<String, IndexedColors> colors = Map.of(
            "รอดำเนินการ", IndexedColors.GREY_25_PERCENT, "พบข้อมูล", IndexedColors.LIGHT_GREEN,
            "หาไม่พบ", IndexedColors.LIGHT_YELLOW, "ผิดพลาด", IndexedColors.ROSE
        );
        Map<String, CellStyle> styles = new HashMap<>();
        colors.forEach((label, color) -> { CellStyle style = book.createCellStyle(); style.setFillForegroundColor(color.getIndex()); style.setFillPattern(FillPatternType.SOLID_FOREGROUND); styles.put(label, style); });
        return styles;
    }

    public String filename(String source, CmsSite site) {
        String stem = source == null ? "workers" : source.replaceFirst("(?i)\\.xlsx$", "");
        stem = stem.replaceAll("[^\\p{L}\\p{N}._-]+", "_").replaceAll("^[._]+|[._]+$", "");
        if (stem.isBlank()) stem = "workers";
        return stem + "_" + site.name() + "_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".xlsx";
    }
}
