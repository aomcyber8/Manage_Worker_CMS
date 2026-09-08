package th.co.sena.workercms;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class StateService {
    private String phase = "idle";
    private boolean active;
    private String mode = "dry-run";
    private String site = "";
    private String filename = "";
    private String revision = "";
    private List<WorkerRecord> records = List.of();
    private final List<WorkerResult> results = new ArrayList<>();
    private final List<String> logs = new ArrayList<>();
    private String error = "";
    private final String token;
    private final EnvConfig config;

    public StateService(EnvConfig config) {
        this.config = config;
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    public synchronized StateSnapshot snapshot() {
        return new StateSnapshot(phase, active, mode, site, filename, revision,
            List.copyOf(records), List.copyOf(results), List.copyOf(logs), error, token, config.configured());
    }
    public String token() { return token; }
    public synchronized boolean active() { return active; }
    public synchronized List<WorkerRecord> records() { return List.copyOf(records); }
    public synchronized List<WorkerResult> results() { return List.copyOf(results); }
    public synchronized String site() { return site; }
    public synchronized String filename() { return filename; }
    public synchronized String revision() { return revision; }

    public synchronized void preview(CmsSite cmsSite, String source, List<WorkerRecord> newRecords) {
        if (active) throw new IllegalStateException("มีงานกำลังทำงาน กรุณารอให้จบก่อน");
        phase = "ready"; records = List.copyOf(newRecords); results.clear(); logs.clear(); error = "";
        filename = source; site = cmsSite.name(); revision = UUID.randomUUID().toString();
    }
    public synchronized void begin(CmsSite cmsSite, String requestedMode, String requestedRevision) {
        if (active) throw new IllegalStateException("มีงานกำลังทำงาน กรุณารอให้จบก่อน");
        if (!site.equals(cmsSite.name())) throw new IllegalArgumentException("ระบบ CMS เปลี่ยนไป กรุณาอ่านไฟล์และตรวจรายการใหม่");
        if (records.isEmpty() || !revision.equals(requestedRevision)) throw new IllegalArgumentException("กรุณาอ่านไฟล์และตรวจรายการใหม่");
        phase = "running"; active = true; mode = requestedMode; results.clear(); logs.clear(); error = "";
    }
    public synchronized void addResult(WorkerResult result) { results.add(result); }
    public synchronized void log(String message) {
        logs.add(message); if (logs.size() > 400) logs.remove(0);
    }
    public synchronized void phase(String value) { phase = value; }
    public synchronized void complete(boolean stopped) { phase = stopped ? "stopped" : "done"; active = false; }
    public synchronized void fail(String message) { phase = "failed"; active = false; error = message; }
}
