package th.co.sena.workercms;

import java.util.function.Consumer;

public interface CmsAutomation {
    Session open(CmsSite site, Consumer<String> log);

    interface Session extends AutoCloseable {
        WorkerResult process(WorkerRecord record, int index, int total, boolean dryRun);
        void logout();
        @Override void close();
    }
}
