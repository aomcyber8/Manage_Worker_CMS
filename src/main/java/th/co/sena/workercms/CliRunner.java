package th.co.sena.workercms;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
public class CliRunner {
    private final EnvConfig config;
    private final ExcelService excel;
    private final WorkflowService workflow;
    public CliRunner(EnvConfig config, ExcelService excel, WorkflowService workflow) { this.config = config; this.excel = excel; this.workflow = workflow; }

    public int run(String[] args) {
        boolean dryRun = has(args, "--dry-run");
        if (!config.configured()) { System.err.println("กรุณาตั้งค่า CMS_USERNAME และ CMS_PASSWORD ในไฟล์ .env"); return 1; }
        Path source = Path.of(option(args, "--file", config.get("EXCEL_FILE_PATH", "./data/worker_list.xlsx")));
        if (!Files.isRegularFile(source)) { System.err.println("ไม่พบไฟล์ Excel: " + source.toAbsolutePath()); return 1; }
        CmsSite site = resolveSite(option(args, "--site", ""));
        int defaultStartRow = config.getInt("EXCEL_START_ROW", 2);
        String explicitHeader = option(args, "--header-row", "");
        int headerRow = explicitHeader.isBlank()
            ? Math.max(1, Integer.parseInt(option(args, "--start-row", String.valueOf(defaultStartRow))) - 1)
            : Integer.parseInt(explicitHeader);
        String sheet = option(args, "--sheet", config.get("EXCEL_SHEET_NAME", ""));
        try {
            List<WorkerRecord> records = excel.parse(Files.readAllBytes(source), headerRow, sheet);
            System.out.printf("CMS %s | %d รายการ | %s%n", site, records.size(), dryRun ? "DRY RUN" : "DEACTIVATE");
            if (!dryRun && !has(args, "--yes")) {
                System.out.print("ต้องการดำเนินการต่อหรือไม่? (y/N): ");
                String answer = new Scanner(System.in, StandardCharsets.UTF_8).nextLine().strip().toLowerCase(Locale.ROOT);
                if (!List.of("y", "yes").contains(answer)) { System.out.println("ยกเลิกการทำงาน"); return 0; }
            }
            Files.createDirectories(Path.of("logs"));
            Path logFile = Path.of("logs", "delete_worker_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".log");
            try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(logFile, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW))) {
                Consumer<String> log = message -> { System.out.println(message); writer.println(message); writer.flush(); };
                List<WorkerResult> results = workflow.runBlocking(records, dryRun, site, log);
                long success = results.stream().filter(result -> result.status() == WorkerStatus.SUCCESS).count();
                long skipped = results.stream().filter(result -> result.status() == WorkerStatus.SKIPPED).count();
                long missing = results.stream().filter(result -> result.status() == WorkerStatus.NOT_FOUND).count();
                log.accept("สรุป: ทั้งหมด " + results.size() + " | สำเร็จ " + success + " | ทดสอบผ่าน " + skipped + " | หาไม่พบ " + missing);
                log.accept("Log file: " + logFile.toAbsolutePath());
            }
            return 0;
        } catch (Exception exception) {
            System.err.println(exception.getMessage()); return 1;
        }
    }
    private CmsSite resolveSite(String requested) {
        if (!requested.isBlank()) return CmsSite.parse(requested);
        String url = config.get("CMS_URL", CmsSite.SENA.baseUrl()).toLowerCase(Locale.ROOT);
        return url.contains("senx.on.lk") ? CmsSite.SENX : CmsSite.SENA;
    }
    private static boolean has(String[] args, String flag) { return Arrays.asList(args).contains(flag); }
    private static String option(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith(name + "=")) return args[i].substring(name.length() + 1);
            if (args[i].equals(name) && i + 1 < args.length) return args[i + 1];
        }
        return fallback;
    }
}
