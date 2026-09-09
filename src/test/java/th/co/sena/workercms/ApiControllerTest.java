package th.co.sena.workercms;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.util.concurrent.Executors;
import java.util.Base64;
import java.io.ByteArrayOutputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApiControllerTest {
    private MockMvc mvc;
    private StateService state;
    private java.util.concurrent.ExecutorService executor;
    private ResignHistoryService resignHistory;
    @BeforeEach void setup() {
        EnvConfig config = new EnvConfig(); state = new StateService(config); executor = Executors.newSingleThreadExecutor();
        ExcelService excel = new ExcelService(new NameService());
        WorkflowService workflow = new WorkflowService(state, Mockito.mock(CmsAutomation.class), config, executor);
        resignHistory = Mockito.mock(ResignHistoryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ApiController(state, excel, workflow, resignHistory))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new LocalSecurityFilter(state)).build();
    }
    @AfterEach void close() { executor.shutdownNow(); }
    @Test void exposesStateUsingExistingJsonContract() throws Exception {
        mvc.perform(get("/api/state").header("Host", "127.0.0.1:80"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("idle")).andExpect(jsonPath("$.token").isNotEmpty());
    }
    @Test void rejectsPostWithoutCsrfToken() throws Exception {
        mvc.perform(post("/api/stop").header("Host", "127.0.0.1:80").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("กรุณาเปิดหน้าเว็บใหม่"));
    }
    @Test void exportWithoutRecordsHasClearError() throws Exception {
        mvc.perform(post("/api/export").header("Host", "127.0.0.1:80").header("X-CSRF-Token", state.token()).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("ไม่มีข้อมูลสำหรับ Export")));
    }
    @Test void previewAndExportKeepTheExistingApiContract() throws Exception {
        String data = Base64.getEncoder().encodeToString(validWorkbook());
        String body = "{\"site\":\"SENA\",\"filename\":\"workers.xlsx\",\"data\":\"" + data + "\",\"header_row\":1,\"sheet\":\"\"}";
        mvc.perform(post("/api/preview").header("Host", "127.0.0.1:80").header("X-CSRF-Token", state.token()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        mvc.perform(get("/api/state").header("Host", "127.0.0.1:80"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.records[0].search_name").value("สมชาย ใจดี"));
        mvc.perform(post("/api/export").header("Host", "127.0.0.1:80").header("X-CSRF-Token", state.token()).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Type", org.hamcrest.Matchers.containsString("spreadsheetml")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Disposition", org.hamcrest.Matchers.containsString("workers_SENA_")));
    }

    @Test void rejectsDeactivateUntilCurrentDryRunIsComplete() throws Exception {
        var record = new WorkerRecord(2, "BK001", "สมชาย ใจดี", "01/08/2026", "BK001", "0001",
            "นายสมชาย ใจดี", "a@sena.co.th", "ผู้จัดการ", "ฝ่ายขาย");
        state.preview(CmsSite.SENA, "workers.xlsx", java.util.List.of(record));

        String body = "{\"site\":\"SENA\",\"mode\":\"deactivate\",\"revision\":\"" + state.revision()
            + "\",\"confirmation\":\"DEACTIVATE\"}";
        mvc.perform(post("/api/start").header("Host", "127.0.0.1:80").header("X-CSRF-Token", state.token())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("ทดสอบรายการให้เสร็จครบ")));
    }
    @Test void importsResignHistoryForSelectedMonth() throws Exception {
        var record = new WorkerRecord(1, "BK001", "สมชาย ใจดี", "01/08/2026", "BK001", "0001", "นายสมชาย ใจดี", "a@sena.co.th", "ผู้จัดการ", "ฝ่ายขาย");
        Mockito.when(resignHistory.load(java.time.YearMonth.of(2026, 8))).thenReturn(java.util.List.of(record));
        mvc.perform(post("/api/resign-history").header("Host", "127.0.0.1:80").header("X-CSRF-Token", state.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"site\":\"SENA\",\"month\":\"2026-08\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));
        mvc.perform(get("/api/state").header("Host", "127.0.0.1:80"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.filename").value("resign-history-2026-08.xlsx"))
            .andExpect(jsonPath("$.records[0].id").value("BK001"));
    }

    @Test void rejectsInvalidResignHistoryMonth() throws Exception {
        mvc.perform(post("/api/resign-history").header("Host", "127.0.0.1:80").header("X-CSRF-Token", state.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"site\":\"SENA\",\"month\":\"08/2026\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("กรุณาระบุเดือนและปีให้ถูกต้อง"));
    }

    private byte[] validWorkbook() throws Exception {
        try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = book.createSheet(); var header = sheet.createRow(0);
            String[] labels = {"วันที่พ้นสภาพ", "ID", "No", "ชื่อ", "E-mail", "ตำแหน่ง", "แผนก"};
            for (int i = 0; i < labels.length; i++) header.createCell(i).setCellValue(labels[i]);
            var row = sheet.createRow(1); row.createCell(1).setCellValue("001"); row.createCell(3).setCellValue("นาย สมชาย ใจดี");
            book.write(output); return output.toByteArray();
        }
    }
}
