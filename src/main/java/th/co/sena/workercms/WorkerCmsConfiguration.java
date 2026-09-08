package th.co.sena.workercms;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class WorkerCmsConfiguration {
    @Bean(destroyMethod = "shutdownNow")
    ExecutorService workflowExecutor() {
        return Executors.newSingleThreadExecutor(Thread.ofPlatform().name("worker-cms-job").factory());
    }
}
