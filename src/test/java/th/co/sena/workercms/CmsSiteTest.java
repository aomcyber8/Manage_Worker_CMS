package th.co.sena.workercms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class CmsSiteTest {
    @Test void mapsExactEmployeeUrls() {
        assertEquals("https://sena.on.lk/Common/Employee", CmsSite.SENA.employeeUrl());
        assertEquals("https://senx.on.lk/Common/Employee", CmsSite.SENX.employeeUrl());
    }
    @Test void buildsCliEmployeeUrlFromOriginOnly() {
        assertEquals("https://sena.on.lk/Common/Employee", CmsSite.employeeUrlFrom("https://sena.on.lk/old/path?x=1"));
    }
}
