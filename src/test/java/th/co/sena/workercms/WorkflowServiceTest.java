package th.co.sena.workercms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorkflowServiceTest {
    private final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
    @AfterEach void close() { executor.shutdownNow(); }

    @Test
    void notFoundContinuesToNextWorker() {
        AtomicInteger calls = new AtomicInteger();
        CmsAutomation automation = fake((record, index) -> {
            calls.incrementAndGet();
            return result(record, index == 1 ? WorkerStatus.NOT_FOUND : WorkerStatus.SKIPPED);
        });
        List<WorkerResult> results = service(automation).runBlocking(records(), true, CmsSite.SENA, message -> {});
        assertEquals(2, calls.get()); assertEquals(List.of(WorkerStatus.NOT_FOUND, WorkerStatus.SKIPPED), results.stream().map(WorkerResult::status).toList());
    }

    @Test
    void failedResultStopsBeforeNextWorker() {
        AtomicInteger calls = new AtomicInteger();
        CmsAutomation automation = fake((record, index) -> { calls.incrementAndGet(); return result(record, WorkerStatus.FAILED); });
        assertThrows(IllegalStateException.class, () -> service(automation).runBlocking(records(), true, CmsSite.SENA, message -> {}));
        assertEquals(1, calls.get());
    }

    private WorkflowService service(CmsAutomation automation) {
        EnvConfig config = new EnvConfig(Map.of("DELAY_BETWEEN_RECORDS", "0"));
        return new WorkflowService(new StateService(config), automation, config, executor);
    }
    private CmsAutomation fake(ResultFactory factory) {
        return (site, log) -> new CmsAutomation.Session() {
            public WorkerResult process(WorkerRecord record, int index, int total, boolean dryRun) { return factory.create(record, index); }
            public void logout() {} public void close() {}
        };
    }
    private List<WorkerRecord> records() {
        return List.of(record(2, "A"), record(3, "B"));
    }
    private WorkerRecord record(int row, String id) {
        return new WorkerRecord(row, id, "สมชาย ใจดี " + id, "", id, "", "นายสมชาย ใจดี " + id, "", "", "");
    }
    private WorkerResult result(WorkerRecord record, WorkerStatus status) {
        return new WorkerResult(record.row(), record.code(), record.name(), record.searchName(), status, status.name());
    }
    @FunctionalInterface interface ResultFactory { WorkerResult create(WorkerRecord record, int index); }
}
