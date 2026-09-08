package th.co.sena.workercms;

import java.util.ArrayList;
import java.util.List;
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
        state.begin(site, mode, revision); stop.set(false);
        List<WorkerRecord> snapshot = state.records();
        executor.submit(() -> execute(snapshot, mode.equals("dry-run"), site, state::log, true));
    }

    public void stop() { stop.set(true); }

    public List<WorkerResult> runBlocking(List<WorkerRecord> records, boolean dryRun, CmsSite site, Consumer<String> log) {
        stop.set(false); return execute(records, dryRun, site, log, false);
    }

    private List<WorkerResult> execute(List<WorkerRecord> records, boolean dryRun, CmsSite site, Consumer<String> log, boolean web) {
        List<WorkerResult> completed = new ArrayList<>();
        try (CmsAutomation.Session session = automation.open(site, log)) {
            for (int index = 0; index < records.size() && !stop.get(); index++) {
                WorkerResult result = session.process(records.get(index), index + 1, records.size(), dryRun);
                completed.add(result); if (web) state.addResult(result);
                if (result.status() == WorkerStatus.FAILED) throw new IllegalStateException("หยุดเมื่อพบข้อผิดพลาด โปรดตรวจสอบผลใน CMS ก่อนเริ่มใหม่");
                if (index + 1 < records.size() && !stop.get()) {
                    try { Thread.sleep(Math.max(0, Math.round(config.getDouble("DELAY_BETWEEN_RECORDS", 0.2) * 1000))); }
                    catch (InterruptedException exception) { Thread.currentThread().interrupt(); stop.set(true); }
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
        return List.copyOf(completed);
    }
}
