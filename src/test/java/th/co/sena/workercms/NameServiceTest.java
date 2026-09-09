package th.co.sena.workercms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NameServiceTest {
    private final NameService service = new NameService();
    @ParameterizedTest
    @CsvSource({
        "'นาย สมชาย  ใจดี','สมชาย ใจดี'",
        "'นางสมหญิง ใจดี','สมหญิง ใจดี'",
        "'นางสาว สมหญิง','สมหญิง'",
        "'น.ส.สมหญิง','สมหญิง'",
        "'Alex   Smith','Alex Smith'"
    })
    void normalizesSupportedPrefixes(String source, String expected) { assertEquals(expected, service.normalize(source)); }

    @ParameterizedTest
    @CsvSource({"'สมชาย ใจดี','ใจดี'", "'สมชาย กลาง ใจดี','กลาง ใจดี'", "'สมชาย',''"})
    void extractsSurname(String source, String expected) { assertEquals(expected, service.surname(source)); }

    @ParameterizedTest
    @CsvSource({
        "'ณัชธญา นิลพานิชย์','ณัชธญา นิลพานิชย์'",
        "'นายศิวกร พวงเพ็ชร','ศิวกร พวงเพ็ชร'",
        "'น.ส.อัญพัชญ์ พีระภักดีพงศ์','อัญพัชญ์ พีระภักดีพงศ์'",
        "'นางสาว อัญพัชญ์ พีระภักดีพงศ์','อัญพัชญ์ พีระภักดีพงศ์'"
    })
    void normalizesReportedEmployeeNames(String source, String expected) {
        assertEquals(service.key(expected), service.key(source));
    }

    @Test
    void normalizesBrowserWhitespaceAndUnicodeComposition() {
        assertEquals("อัญพัชญ์ พีระภักดีพงศ์", service.normalize("นางสาว อัญพัชญ์​ พีระภักดีพงศ์"));
        assertEquals(service.key("อัญพัชญ์ พีระภักดีพงศ์"),
            service.key("นางสาว อัญพัชญ์ พีระภักดีพงศ์"));
    }
}
