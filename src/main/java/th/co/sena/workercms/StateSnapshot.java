package th.co.sena.workercms;

import java.util.List;

public record StateSnapshot(
    String phase,
    boolean active,
    String mode,
    String site,
    String filename,
    String revision,
    List<WorkerRecord> records,
    List<WorkerResult> results,
    List<String> logs,
    String error,
    String token,
    boolean configured
) {}
