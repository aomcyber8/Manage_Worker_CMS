package th.co.sena.workercms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorkflowServiceTest {
    private final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
    @AfterEach void close() { executor.shutdownNow(); }

    @Test
    void notFoundContinuesToNextWorker() {
        AtomicInteger calls = new AtomicInteger();
        CmsAutomation automation = fake((record, index, dryRun) -> {
            calls.incrementAndGet();
            return result(record, index == 1 ? WorkerStatus.NOT_FOUND : WorkerStatus.SKIPPED);
        });
        List<WorkerResult> results = service(automation).runBlocking(records(), true, CmsSite.SENA, message -> {});
        assertEquals(2, calls.get());
        assertEquals(List.of(WorkerStatus.NOT_FOUND, WorkerStatus.SKIPPED),
            results.stream().map(WorkerResult::status).toList());
    }

    @Test
    void failedResultStopsBeforeNextWorker() {
        AtomicInteger calls = new AtomicInteger();
        CmsAutomation automation = fake((record, index, dryRun) -> {
            calls.incrementAndGet();
            return result(record, WorkerStatus.FAILED);
        });
        assertThrows(IllegalStateException.class,
            () -> service(automation).runBlocking(records(), true, CmsSite.SENA, message -> {}));
        assertEquals(1, calls.get());
    }

    @Test
    void cliDeactivateDiscoversAllWorkersThenProcessesOnlyFoundRows() {
        List<String> calls = new ArrayList<>();
        CmsAutomation automation = fake((record, index, dryRun) -> {
            calls.add((dryRun ? "test:" : "deactivate:") + record.id());
            if (dryRun) {
                return result(record, "B".equals(record.id()) ? WorkerStatus.NOT_FOUND : WorkerStatus.SKIPPED);
            }
            return result(record, WorkerStatus.SUCCESS);
        });

        List<WorkerResult> results = service(automation).runBlocking(records("A", "B", "C"), false,
            CmsSite.SENA, message -> {});

        assertEquals(List.of("test:A", "test:B", "test:C", "deactivate:A", "deactivate:C"), calls);
        assertEquals(List.of(WorkerStatus.SUCCESS, WorkerStatus.NOT_FOUND, WorkerStatus.SUCCESS),
            results.stream().map(WorkerResult::status).toList());
    }

    @Test
    void webDeactivateUsesOnlyExistingFoundDryRunRows() throws Exception {
        EnvConfig config = configured();
        StateService state = new StateService(config);
        List<WorkerRecord> records = records("A", "B", "C");
        state.preview(CmsSite.SENA, "test.xlsx", records);
        String revision = state.revision();
        state.begin(CmsSite.SENA, "dry-run", revision);
        state.addResult(result(records.get(0), WorkerStatus.SKIPPED));
        state.addResult(result(records.get(1), WorkerStatus.NOT_FOUND));
        state.addResult(result(records.get(2), WorkerStatus.SKIPPED));
        state.complete(false);

        List<String> calls = new ArrayList<>();
        CountDownLatch complete = new CountDownLatch(2);
        CmsAutomation automation = fake((record, index, dryRun) -> {
            calls.add((dryRun ? "test:" : "deactivate:") + record.id());
            complete.countDown();
            return result(record, WorkerStatus.SUCCESS);
        });
        WorkflowService workflow = new WorkflowService(state, automation, config, executor);

        workflow.start(CmsSite.SENA, "deactivate", revision, "DEACTIVATE");
        assertTrue(complete.await(2, TimeUnit.SECONDS));
        waitUntilInactive(state);

        assertEquals(List.of("deactivate:A", "deactivate:C"), calls);
        assertEquals(List.of(WorkerStatus.SUCCESS, WorkerStatus.NOT_FOUND, WorkerStatus.SUCCESS),
            state.results().stream().map(WorkerResult::status).toList());
    }

    @Test
    void webDeactivateRequiresCompleteDryRun() {
        EnvConfig config = configured();
        StateService state = new StateService(config);
        state.preview(CmsSite.SENA, "test.xlsx", records());
        WorkflowService workflow = new WorkflowService(state, fake((record, index, dryRun) ->
            result(record, WorkerStatus.SUCCESS)), config, executor);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> workflow.start(CmsSite.SENA, "deactivate", state.revision(), "DEACTIVATE"));

        assertTrue(error.getMessage().contains("ทดสอบรายการ"));
    }

    @Test
    void deactivateStopsWhenPreviouslyFoundWorkerDisappears() {
        AtomicInteger calls = new AtomicInteger();
        CmsAutomation automation = fake((record, index, dryRun) -> {
            calls.incrementAndGet();
            return result(record, dryRun ? WorkerStatus.SKIPPED : WorkerStatus.NOT_FOUND);
        });

        assertThrows(IllegalStateException.class,
            () -> service(automation).runBlocking(List.of(record(2, "A")), false, CmsSite.SENA, message -> {}));
        assertEquals(2, calls.get());
    }

    @Test
    void stateReplacesResultForTheSameExcelRow() {
        EnvConfig config = new EnvConfig(Map.of());
        StateService state = new StateService(config);
        WorkerRecord record = record(2, "A");
        state.preview(CmsSite.SENA, "test.xlsx", List.of(record));
        state.addResult(result(record, WorkerStatus.SKIPPED));
        state.addResult(result(record, WorkerStatus.SUCCESS));

        assertEquals(1, state.results().size());
        assertEquals(WorkerStatus.SUCCESS, state.results().getFirst().status());
    }

    private void waitUntilInactive(StateService state) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (state.active() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(!state.active(), "workflow did not finish");
    }
    private EnvConfig configured() {
        return new EnvConfig(Map.of("DELAY_BETWEEN_RECORDS", "0", "CMS_USERNAME", "user", "CMS_PASSWORD", "pass"));
    }
    private WorkflowService service(CmsAutomation automation) {
        EnvConfig config = new EnvConfig(Map.of("DELAY_BETWEEN_RECORDS", "0"));
        return new WorkflowService(new StateService(config), automation, config, executor);
    }
    private CmsAutomation fake(ResultFactory factory) {
        return (site, log) -> new CmsAutomation.Session() {
            public WorkerResult process(WorkerRecord record, int index, int total, boolean dryRun) {
                return factory.create(record, index, dryRun);
            }
            public void logout() {}
            public void close() {}
        };
    }
    private List<WorkerRecord> records() { return records("A", "B"); }
    private List<WorkerRecord> records(String... ids) {
        List<WorkerRecord> records = new ArrayList<>();
        for (int index = 0; index < ids.length; index++) records.add(record(index + 2, ids[index]));
        return List.copyOf(records);
    }
    private WorkerRecord record(int row, String id) {
        return new WorkerRecord(row, id, "สมชาย ใจดี " + id, "", id, "", "นายสมชาย ใจดี " + id, "", "", "");
    }
    private WorkerResult result(WorkerRecord record, WorkerStatus status) {
        return new WorkerResult(record.row(), record.code(), record.name(), record.searchName(), status, status.name());
    }
    @FunctionalInterface interface ResultFactory {
        WorkerResult create(WorkerRecord record, int index, boolean dryRun);
    }
}