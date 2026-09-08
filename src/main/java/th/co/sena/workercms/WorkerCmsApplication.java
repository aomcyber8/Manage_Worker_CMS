package th.co.sena.workercms;

import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

public class WorkerCmsApplication {
    public static void main(String[] args) {
        boolean cli = Arrays.asList(args).contains("--cli");
        SpringApplication application = new SpringApplication(WorkerCmsConfiguration.class);
        if (cli) application.setWebApplicationType(WebApplicationType.NONE);
        ConfigurableApplicationContext context = application.run(args);
        if (cli) {
            int exitCode = context.getBean(CliRunner.class).run(args);
            context.close();
            System.exit(exitCode);
        }
    }
}
