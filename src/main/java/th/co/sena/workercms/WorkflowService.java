package th.co.sena.workercms;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

@Service
public class WorkflowService {
    private final StateService state;
    private final CmsAutomation automation;
    private final EnvConfig config;
    private final ExecutorService executor;
    private final AtomicBoolean stop = new AtomicBoolean();

    public WorkflowService(StateService state, CmsAutomation automation, EnvConfig config, ExecutorService executor) {
        this.state = state; this.automation = automation; this.config = config; this.executor = executor;
    }

    public void start(CmsSite site, String mode, String revision, String confirmation) {
        if (!List.of("dry-run", "deactivate").contains(mode)) throw new IllegalArgumentException("โหมดไม่ถูกต้อง");
        if (mode.equals("deactivate") && !"DEACTIVATE".equals(confirmation)) throw new IllegalArgumentException("พิมพ์ DEACTIVATE เพื่อยืนยันการปิดสิทธิ์ใช้งาน");
        if (!config.configured()) throw new IllegalArgumentException("กรุณาตั้งค่าบัญชี CMS ใน .env ก่อน");
        state.begin(site, mode, revision);
        stop.set(false);
        List<WorkerRecord> records = state.records();
        List<WorkerResult> existingResults = state.results();
        executor.submit(() -> execute(records, mode.equals("dry-run"), site, state::log, true, existingResults));
    }

    public void stop() { stop.set(true); }

    public List<WorkerResult> runBlocking(List<WorkerRecord> records, boolean dryRun, CmsSite site, Consumer<String> log) {
        stop.set(false);
        return execute(records, dryRun, site, log, false, List.of());
    }

    private List<WorkerResult> execute(List<WorkerRecord> records, boolean dryRun, CmsSite site,
                                       Consumer<String> log, boolean web, List<WorkerResult> initialResults) {
        Map<Integer, WorkerResult> completed = new LinkedHashMap<>();
        initialResults.forEach(result -> completed.put(result.row(), result));
        try (CmsAutomation.Session session = automation.open(site, log)) {
            if (dryRun) {
                processPass(session, records, true, completed, web);
            } else {
                if (initialResults.isEmpty()) {
                    log.accept("ช่วงค้นหา: ทดสอบรายการทั้งหมดสำหรับ command line");
                    processPass(session, records, true, completed, web);
                }
                List<WorkerRecord> found = records.stream()
                    .filter(record -> {
                        WorkerResult result = completed.get(record.row());
                        return result != null && result.status() == WorkerStatus.SKIPPED;
                    })
                    .toList();
                log.accept("ปิดสิทธิ์ใช้งาน " + found.size() + " รายการที่มีผล Dry Run เป็นพบข้อมูล");
                if (web) state.phase("deactivating");
                for (int index = 0; index < found.size() && !stop.get(); index++) {
                    WorkerRecord record = found.get(index);
                    WorkerResult result = session.process(record, index + 1, found.size(), false);
                    if (result.status() == WorkerStatus.NOT_FOUND) {
                        String message = "Error: รอบปิดสิทธิ์ค้นหา '" + record.searchName()
                            + "' ไม่พบ ทั้งที่ผล Dry Run เป็นพบข้อมูล";
                        log.accept(message);
                        result = new WorkerResult(record.row(), record.code(), record.name(), record.searchName(),
                            WorkerStatus.FAILED, message);
                    }
                    putResult(completed, result, web);
                    failIfNeeded(result);
                    delay(index + 1 < found.size());
                }
            }
            if (web) state.phase("finishing");
            session.logout();
            if (web) state.complete(stop.get());
        } catch (Exception exception) {
            log.accept(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
            if (web) state.fail("งานหยุดเนื่องจากข้อผิดพลาด ดูรายละเอียดในบันทึกการทำงาน");
            else throw exception instanceof RuntimeException runtime ? runtime : new IllegalStateException(exception);
        }
        return records.stream().map(record -> completed.get(record.row())).filter(Objects::nonNull).toList();
    }

    private void processPass(CmsAutomation.Session session, List<WorkerRecord> records, boolean dryRun,
                             Map<Integer, WorkerResult> completed, boolean web) {
        for (int index = 0; index < records.size() && !stop.get(); index++) {
            WorkerResult result = session.process(records.get(index), index + 1, records.size(), dryRun);
            putResult(completed, result, web);
            failIfNeeded(result);
            delay(index + 1 < records.size());
        }
    }

    private void putResult(Map<Integer, WorkerResult> completed, WorkerResult result, boolean web) {
        completed.put(result.row(), result);
        if (web) state.addResult(result);
    }

    private void failIfNeeded(WorkerResult result) {
        if (result.status() == WorkerStatus.FAILED) {
            throw new IllegalStateException("หยุดเมื่อพบข้อผิดพลาด โปรดตรวจสอบผลใน CMS ก่อนเริ่มใหม่");
        }
    }

    private void delay(boolean hasNext) {
        if (!hasNext || stop.get()) return;
        try { Thread.sleep(Math.max(0, Math.round(config.getDouble("DELAY_BETWEEN_RECORDS", 0.2) * 1000))); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); stop.set(true); }
    }
}
