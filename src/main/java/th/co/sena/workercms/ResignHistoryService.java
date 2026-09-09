package th.co.sena.workercms;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ResignHistoryService {
    private static final DateTimeFormatter SOURCE_DATE = DateTimeFormatter.ofPattern("d/M/uuuu");
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu");
    private static final int MAX_PAGES = 2_000;

    @FunctionalInterface
    interface PageFetcher {
        String fetch(int page) throws Exception;
    }

    private final NameService names;
    private final PageFetcher fetcher;

    @Autowired
    public ResignHistoryService(EnvConfig config, NameService names) {
        this(names, httpFetcher(config));
    }

    ResignHistoryService(NameService names, PageFetcher fetcher) {
        this.names = names;
        this.fetcher = fetcher;
    }

    public List<WorkerRecord> load(YearMonth month) {
        if (month == null) throw new IllegalArgumentException("กรุณาระบุเดือนที่ต้องการดึงข้อมูล");
        List<SourceRow> selected = new ArrayList<>();
        int totalPages = 1;

        for (int page = 1; page <= totalPages; page++) {
            ParsedPage parsed = fetch(page);
            if (page == 1) totalPages = Math.min(parsed.totalPages(), MAX_PAGES);
            boolean reachedOlderMonth = false;
            LocalDate firstDay = month.atDay(1);
            for (SourceRow row : parsed.rows()) {
                YearMonth rowMonth = YearMonth.from(row.date());
                if (rowMonth.equals(month)) selected.add(row);
                if (row.date().isBefore(firstDay)) reachedOlderMonth = true;
            }
            if (reachedOlderMonth) break;
        }

        if (selected.isEmpty()) {
            throw new IllegalArgumentException("ไม่พบข้อมูลผู้ลาออกในเดือน " + month);
        }
        return validateAndConvert(selected);
    }

    private ParsedPage fetch(int page) {
        try {
            return parsePage(fetcher.fetch(page));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("เชื่อมต่อเว็บข้อมูลผู้ลาออกไม่สำเร็จที่หน้า " + page, exception);
        }
    }

    ParsedPage parsePage(String html) {
        Document document = Jsoup.parse(html == null ? "" : html);
        Element table = document.selectFirst("table#data");
        if (table == null) throw new IllegalStateException("ไม่พบตารางประวัติผู้ที่ลาออกแล้วในหน้าเว็บ");

        int totalPages = table.select("select[name=page] option").stream()
            .map(Element::text)
            .map(String::strip)
            .filter(value -> value.matches("\\d+"))
            .mapToInt(Integer::parseInt)
            .max().orElse(1);

        List<SourceRow> rows = new ArrayList<>();
        for (Element tableRow : table.select("tr")) {
            List<Element> cells = tableRow.select("> td");
            if (cells.size() < 7) continue;
            String dateText = clean(cells.get(0).text());
            LocalDate date;
            try {
                date = LocalDate.parse(dateText, SOURCE_DATE);
            } catch (DateTimeParseException ignored) {
                continue;
            }
            rows.add(new SourceRow(date, clean(cells.get(1).text()), clean(cells.get(2).text()),
                clean(cells.get(3).text()), clean(cells.get(4).text()), clean(cells.get(5).text()),
                clean(cells.get(6).text())));
        }
        return new ParsedPage(totalPages, List.copyOf(rows));
    }

    private List<WorkerRecord> validateAndConvert(List<SourceRow> source) {
        List<WorkerRecord> records = new ArrayList<>();
        Map<String, Integer> seenNames = new HashMap<>();
        for (int index = 0; index < source.size(); index++) {
            SourceRow row = source.get(index);
            int displayRow = index + 1;
            List<String> missing = new ArrayList<>();
            if (row.id().isBlank()) missing.add("ID");
            if (row.name().isBlank()) missing.add("ชื่อ");
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("ข้อมูลเว็บรายการที่ " + displayRow + " ไม่มีข้อมูล: " + String.join(", ", missing));
            }
            String searchName = names.normalize(row.name());
            if (searchName.isBlank()) {
                throw new IllegalArgumentException("ข้อมูลเว็บรายการที่ " + displayRow + " ไม่มีชื่อสำหรับค้นหา");
            }
            Integer previous = seenNames.putIfAbsent(names.key(searchName), displayRow);
            if (previous != null) {
                throw new IllegalArgumentException("ชื่อซ้ำหลังตัดคำนำหน้า: " + searchName
                    + " (รายการที่ " + previous + " และ " + displayRow + ")");
            }
            records.add(new WorkerRecord(displayRow, row.id(), searchName, row.date().format(DISPLAY_DATE),
                row.id(), row.no(), row.name(), row.email(), row.position(), row.department()));
        }
        return List.copyOf(records);
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace(' ', ' ').replaceAll("[\\s\\u200B\\uFEFF]+", " ").strip();
    }

    private static PageFetcher httpFetcher(EnvConfig config) {
        String source = config.get("RESIGN_HISTORY_URL", "http://192.168.0.161/resign/index2.php");
        URI base;
        try {
            base = URI.create(source);
        } catch (Exception exception) {
            throw new IllegalArgumentException("RESIGN_HISTORY_URL ไม่ถูกต้อง", exception);
        }
        if (base.getHost() == null || !List.of("http", "https").contains(base.getScheme())) {
            throw new IllegalArgumentException("RESIGN_HISTORY_URL ต้องเป็น http หรือ https");
        }
        int timeoutSeconds = Math.max(3, config.getInt("RESIGN_HISTORY_TIMEOUT_SECONDS", 15));
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        return page -> {
            String separator = source.contains("?") ? "&" : "?";
            URI uri = URI.create(source + separator + "page=" + page);
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Accept", "text/html")
                .GET().build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("เว็บข้อมูลผู้ลาออกตอบกลับ HTTP " + response.statusCode());
            }
            return new String(response.body(), StandardCharsets.UTF_8);
        };
    }

    record SourceRow(LocalDate date, String id, String no, String name, String email, String position, String department) {}
    record ParsedPage(int totalPages, List<SourceRow> rows) {}
}


