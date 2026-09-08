package th.co.sena.workercms;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ApiController {
    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final StateService state;
    private final ExcelService excel;
    private final WorkflowService workflow;
    public ApiController(StateService state, ExcelService excel, WorkflowService workflow) { this.state = state; this.excel = excel; this.workflow = workflow; }

    @GetMapping("/state") public StateSnapshot state() { return state.snapshot(); }

    @PostMapping("/preview")
    public Map<String, Boolean> preview(@RequestBody PreviewRequest request) {
        if (state.active()) throw new IllegalStateException("มีงานกำลังทำงาน กรุณารอให้จบก่อน");
        CmsSite site = CmsSite.parse(request.site());
        if (request.filename() == null || !request.filename().toLowerCase().endsWith(".xlsx")) throw new IllegalArgumentException("เลือกไฟล์ .xlsx เท่านั้น");
        byte[] raw;
        try { raw = Base64.getDecoder().decode(request.data()); }
        catch (Exception exception) { throw new IllegalArgumentException("ข้อมูลไฟล์ไม่ถูกต้อง"); }
        state.preview(site, request.filename(), excel.parse(raw, request.headerRow() == null ? 1 : request.headerRow(), request.sheet()));
        return Map.of("ok", true);
    }

    @PostMapping("/start")
    public Map<String, Boolean> start(@RequestBody StartRequest request) {
        workflow.start(CmsSite.parse(request.site()), request.mode(), request.revision(), request.confirmation());
        return Map.of("ok", true);
    }

    @PostMapping("/stop") public Map<String, Boolean> stop() { workflow.stop(); return Map.of("ok", true); }

    @PostMapping("/export")
    public ResponseEntity<byte[]> export() {
        if (state.active()) throw new IllegalStateException("มีงานกำลังทำงาน กรุณารอให้จบก่อน");
        byte[] content = excel.export(state.records(), state.results());
        CmsSite site = CmsSite.parse(state.site());
        String filename = excel.filename(state.filename(), site);
        return ResponseEntity.ok().contentType(XLSX)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
            .body(content);
    }

    public record PreviewRequest(String site, String filename, String data, @JsonProperty("header_row") Integer headerRow, String sheet) {}
    public record StartRequest(String site, String mode, String revision, String confirmation) {}
}
