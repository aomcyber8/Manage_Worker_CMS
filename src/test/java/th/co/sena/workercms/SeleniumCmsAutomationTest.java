package th.co.sena.workercms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;

class SeleniumCmsAutomationTest {
    private final EnvConfig config = new EnvConfig(Map.of("WAIT_TIMEOUT", "1"));
    private final NameService names = new NameService();

    @Test
    void rejectsEmployeePageOnWrongDomain() {
        ChromeDriver driver = mock(ChromeDriver.class);
        when(driver.getCurrentUrl()).thenReturn("https://senx.on.lk/Common/Employee");
        var session = new SeleniumCmsAutomation(config, names).new SeleniumSession(driver, CmsSite.SENA, message -> {});
        assertThrows(IllegalStateException.class, session::openEmployeePage);
    }

    @Test
    void reportsCmsAccessDeniedWithAccessId() {
        ChromeDriver driver = mock(ChromeDriver.class);
        when(driver.getCurrentUrl()).thenReturn("https://sena.on.lk/Error/AccessDenied");
        when(driver.getPageSource()).thenReturn("You are not authorized to view this page. Role is not defined. Access Id : 89");

        var session = new SeleniumCmsAutomation(config, names).new SeleniumSession(driver, CmsSite.SENA, message -> {});
        IllegalStateException error = assertThrows(IllegalStateException.class, session::openEmployeePage);

        assertTrue(error.getMessage().contains("ไม่มีสิทธิ์"));
        assertTrue(error.getMessage().contains("Access Id 89"));
    }

    @Test
    void waitsForEmployeeRedirectAndPairedSearchControl() {
        ChromeDriver driver = mock(ChromeDriver.class);
        WebElement search = mock(WebElement.class), parent = mock(WebElement.class), button = mock(WebElement.class);
        pairSearch(search, parent, button);
        when(driver.getCurrentUrl()).thenReturn(
            "https://sena.on.lk/?Length=0",
            "https://sena.on.lk/Common/Employee",
            "https://sena.on.lk/Common/Employee",
            "https://sena.on.lk/Common/Employee"
        );
        when(driver.findElements(SeleniumCmsAutomation.EMPLOYEE_SEARCH_INPUTS)).thenReturn(List.of(search));

        var session = new SeleniumCmsAutomation(config, names).new SeleniumSession(driver, CmsSite.SENA, message -> {});
        session.openEmployeePage();

        verify(driver).get("https://sena.on.lk/Common/Employee");
    }

    @Test
    void dryRunStopsAtMatchingGridRowWithoutOpeningDetailOrReadingStatus() {
        ChromeDriver driver = mock(ChromeDriver.class);
        WebElement decoy = mock(WebElement.class), input = mock(WebElement.class), parent = mock(WebElement.class);
        WebElement row = mock(WebElement.class), cell = mock(WebElement.class), link = mock(WebElement.class);
        WebElement label = mock(WebElement.class), toggle = mock(WebElement.class), save = mock(WebElement.class);
        WebElement searchButton = mock(WebElement.class);
        when(decoy.getAttribute("placeholder")).thenReturn("Search");
        when(decoy.isDisplayed()).thenReturn(true);
        when(decoy.isEnabled()).thenReturn(true);
        when(decoy.findElements(SeleniumCmsAutomation.SEARCH_BUTTON_WITHIN)).thenReturn(List.of());
        when(decoy.findElement(any(By.class))).thenThrow(new NoSuchElementException("no paired button"));
        pairSearch(input, parent, searchButton);
        when(driver.findElements(SeleniumCmsAutomation.EMPLOYEE_SEARCH_INPUTS)).thenReturn(List.of(decoy, input));
        when(driver.navigate()).thenReturn(mock(WebDriver.Navigation.class));
        when(driver.findElements(SeleniumCmsAutomation.GRID_ROWS)).thenReturn(List.of(), List.of(row));
        for (By selector : SeleniumCmsAutomation.NO_RESULTS) when(driver.findElements(selector)).thenReturn(List.of());
        when(row.isDisplayed()).thenReturn(true);
        when(row.getText()).thenReturn("นาย สมชาย ใจดี");
        when(row.findElements(SeleniumCmsAutomation.RESULT_CELLS)).thenReturn(List.of(cell));
        when(cell.isDisplayed()).thenReturn(true);
        when(cell.getText()).thenReturn("นาย สมชาย ใจดี");
        when(cell.findElements(SeleniumCmsAutomation.RESULT_LINKS)).thenReturn(List.of(link));
        when(link.isDisplayed()).thenReturn(true);
        when(link.getText()).thenReturn("นาย สมชาย ใจดี");
        when(driver.findElements(SeleniumCmsAutomation.STATUS)).thenReturn(List.of(label));
        when(label.isDisplayed()).thenReturn(true);
        when(label.getText()).thenReturn("Employee");
        when(label.findElements(SeleniumCmsAutomation.TOGGLE)).thenReturn(List.of(toggle));
        when(toggle.isEnabled()).thenReturn(true);
        when(toggle.isDisplayed()).thenReturn(true);
        when(driver.findElements(SeleniumCmsAutomation.SAVE)).thenReturn(List.of(save));
        when(save.isDisplayed()).thenReturn(true);
        when(save.isEnabled()).thenReturn(true);

        var session = new SeleniumCmsAutomation(config, names).new SeleniumSession(driver, CmsSite.SENA, message -> {});
        WorkerRecord record = new WorkerRecord(2, "EMP001", "สมชาย ใจดี", "", "EMP001", "", "นาย สมชาย ใจดี", "", "", "");
        WorkerResult result = session.process(record, 1, 1, true);

        assertEquals(WorkerStatus.SKIPPED, result.status());
        assertEquals("พบข้อมูล — ชื่อตรงหนึ่งรายการ", result.message());
        verify(input).sendKeys("สมชาย ใจดี");
        verify(decoy, never()).sendKeys("สมชาย ใจดี");
        verify(searchButton).click();
        verify(link, never()).click();
        verify(driver, never()).findElements(SeleniumCmsAutomation.STATUS);
    }

    @Test
    void deactivateStillOpensDetailAndChangesStatus() {
        ChromeDriver driver = mock(ChromeDriver.class);
        WebElement input = mock(WebElement.class), parent = mock(WebElement.class), searchButton = mock(WebElement.class);
        WebElement row = mock(WebElement.class), cell = mock(WebElement.class), link = mock(WebElement.class);
        WebElement label = mock(WebElement.class), toggle = mock(WebElement.class), save = mock(WebElement.class);
        WebElement saved = mock(WebElement.class);
        pairSearch(input, parent, searchButton);
        when(driver.findElements(SeleniumCmsAutomation.EMPLOYEE_SEARCH_INPUTS)).thenReturn(List.of(input));
        when(driver.findElements(SeleniumCmsAutomation.GRID_ROWS)).thenReturn(List.of(), List.of(row));
        for (By selector : SeleniumCmsAutomation.NO_RESULTS) when(driver.findElements(selector)).thenReturn(List.of());
        when(row.isDisplayed()).thenReturn(true);
        when(row.getText()).thenReturn("นาย สมชาย ใจดี");
        when(row.findElements(SeleniumCmsAutomation.RESULT_CELLS)).thenReturn(List.of(cell));
        when(cell.isDisplayed()).thenReturn(true);
        when(cell.getText()).thenReturn("นาย สมชาย ใจดี");
        when(cell.findElements(SeleniumCmsAutomation.RESULT_LINKS)).thenReturn(List.of(link));
        when(link.isDisplayed()).thenReturn(true);
        when(link.getText()).thenReturn("นาย สมชาย ใจดี");
        when(driver.findElements(SeleniumCmsAutomation.STATUS)).thenReturn(List.of(label));
        when(label.isDisplayed()).thenReturn(true);
        when(label.getText()).thenReturn("Employee", "Ex-Employee", "Ex-Employee");
        when(label.findElements(SeleniumCmsAutomation.TOGGLE)).thenReturn(List.of(toggle));
        when(toggle.isEnabled()).thenReturn(true);
        when(toggle.isDisplayed()).thenReturn(true);
        when(driver.findElements(SeleniumCmsAutomation.SAVE)).thenReturn(List.of(save));
        when(save.isDisplayed()).thenReturn(true);
        when(save.isEnabled()).thenReturn(true);
        when(driver.findElements(SeleniumCmsAutomation.SAVE_SUCCESS)).thenReturn(List.of(saved));
        when(saved.isDisplayed()).thenReturn(true);
        when(driver.getCurrentUrl()).thenReturn("https://sena.on.lk/Common/Employee/Form?id=1");
        when(driver.navigate()).thenReturn(mock(WebDriver.Navigation.class));

        var session = new SeleniumCmsAutomation(config, names).new SeleniumSession(driver, CmsSite.SENA, message -> {});
        WorkerRecord record = new WorkerRecord(2, "EMP001", "สมชาย ใจดี", "", "EMP001", "", "นาย สมชาย ใจดี", "", "", "");
        WorkerResult result = session.process(record, 1, 1, false);

        assertEquals(WorkerStatus.SUCCESS, result.status());
        verify(link).click();
        verify(toggle).click();
        verify(save).click();
    }

    @Test
    void emptyTableFallsBackToSurnameAndReturnsNotFound() {
        WebDriver driver = mock(WebDriver.class);
        WebElement input = mock(WebElement.class), parent = mock(WebElement.class), button = mock(WebElement.class);
        pairSearch(input, parent, button);
        when(driver.findElements(SeleniumCmsAutomation.EMPLOYEE_SEARCH_INPUTS)).thenReturn(List.of(input));
        when(driver.findElements(SeleniumCmsAutomation.GRID_ROWS)).thenReturn(List.of());
        for (By selector : SeleniumCmsAutomation.NO_RESULTS) when(driver.findElements(selector)).thenReturn(List.of());

        var session = new SeleniumCmsAutomation(config, names).new SeleniumSession(driver, CmsSite.SENA, message -> {});
        WorkerRecord record = new WorkerRecord(2, "EMP001", "สมชาย ใจดี", "", "EMP001", "", "นายสมชาย ใจดี", "", "", "");
        WorkerResult result = session.process(record, 1, 1, true);

        assertEquals(WorkerStatus.NOT_FOUND, result.status());
        verify(input).sendKeys("สมชาย ใจดี");
        verify(input).sendKeys("ใจดี");
    }

    @Test
    void unchangedNonEmptyTableReturnsClearFailure() {
        WebDriver driver = mock(WebDriver.class);
        WebElement input = mock(WebElement.class), parent = mock(WebElement.class), button = mock(WebElement.class);
        WebElement staleRow = mock(WebElement.class), staleCell = mock(WebElement.class);
        pairSearch(input, parent, button);
        when(driver.findElements(SeleniumCmsAutomation.EMPLOYEE_SEARCH_INPUTS)).thenReturn(List.of(input));
        when(driver.findElements(SeleniumCmsAutomation.GRID_ROWS)).thenReturn(List.of(staleRow));
        when(staleRow.isDisplayed()).thenReturn(true);
        when(staleRow.getText()).thenReturn("นาย คนเดิม ทดสอบ");
        when(staleRow.findElements(SeleniumCmsAutomation.RESULT_CELLS)).thenReturn(List.of(staleCell));
        when(staleCell.isDisplayed()).thenReturn(true);
        when(staleCell.getText()).thenReturn("นาย คนเดิม ทดสอบ");
        for (By selector : SeleniumCmsAutomation.NO_RESULTS) when(driver.findElements(selector)).thenReturn(List.of());

        var session = new SeleniumCmsAutomation(config, names).new SeleniumSession(driver, CmsSite.SENA, message -> {});
        WorkerRecord record = new WorkerRecord(2, "EMP001", "สมชาย ใจดี", "", "EMP001", "", "นายสมชาย ใจดี", "", "", "");
        WorkerResult result = session.process(record, 1, 1, true);

        assertEquals(WorkerStatus.FAILED, result.status());
        assertTrue(result.message().contains("ตารางผลค้นหาไม่อัปเดต"), result.message());
        assertTrue(result.message().contains("สมชาย ใจดี"), result.message());
        verify(input).sendKeys(Keys.RETURN);
    }

    @Test
    void failsWhenEmployeeSearchHasNoPairedMagnifier() {
        WebDriver driver = mock(WebDriver.class);
        WebElement input = mock(WebElement.class);
        when(input.getAttribute("placeholder")).thenReturn("Search");
        when(input.isDisplayed()).thenReturn(true);
        when(input.isEnabled()).thenReturn(true);
        when(input.findElements(SeleniumCmsAutomation.SEARCH_BUTTON_WITHIN)).thenReturn(List.of());
        when(input.findElement(any(By.class))).thenThrow(new NoSuchElementException("no parent"));
        when(driver.findElements(SeleniumCmsAutomation.EMPLOYEE_SEARCH_INPUTS)).thenReturn(List.of(input));

        var session = new SeleniumCmsAutomation(config, names).new SeleniumSession(driver, CmsSite.SENA, message -> {});
        WorkerResult result = session.process(
            new WorkerRecord(2, "EMP001", "สมชาย ใจดี", "", "EMP001", "", "นายสมชาย ใจดี", "", "", ""), 1, 1, true);

        assertEquals(WorkerStatus.FAILED, result.status());
        assertTrue(result.message().contains("ไม่พบช่อง Employee Search"), result.message());
    }

    @Test
    void failsWhenEmployeeSearchControlIsAmbiguous() {
        WebDriver driver = mock(WebDriver.class);
        WebElement input = mock(WebElement.class), parent = mock(WebElement.class);
        WebElement firstButton = mock(WebElement.class), secondButton = mock(WebElement.class);
        when(input.getAttribute("placeholder")).thenReturn("Search");
        when(input.isDisplayed()).thenReturn(true);
        when(input.isEnabled()).thenReturn(true);
        when(input.findElements(SeleniumCmsAutomation.SEARCH_BUTTON_WITHIN)).thenReturn(List.of());
        when(input.findElement(any(By.class))).thenReturn(parent);
        when(parent.findElements(SeleniumCmsAutomation.SEARCH_BUTTON_WITHIN)).thenReturn(List.of(firstButton, secondButton));
        when(firstButton.isDisplayed()).thenReturn(true);
        when(firstButton.isEnabled()).thenReturn(true);
        when(secondButton.isDisplayed()).thenReturn(true);
        when(secondButton.isEnabled()).thenReturn(true);
        when(driver.findElements(SeleniumCmsAutomation.EMPLOYEE_SEARCH_INPUTS)).thenReturn(List.of(input));

        var session = new SeleniumCmsAutomation(config, names).new SeleniumSession(driver, CmsSite.SENA, message -> {});
        WorkerResult result = session.process(
            new WorkerRecord(2, "EMP001", "สมชาย ใจดี", "", "EMP001", "", "นายสมชาย ใจดี", "", "", ""), 1, 1, true);

        assertEquals(WorkerStatus.FAILED, result.status());
        assertTrue(result.message().contains("มากกว่าหนึ่งชุด"), result.message());
    }

    private void pairSearch(WebElement input, WebElement parent, WebElement button) {
        when(input.getAttribute("placeholder")).thenReturn("Search");
        when(input.isDisplayed()).thenReturn(true);
        when(input.isEnabled()).thenReturn(true);
        when(input.findElements(SeleniumCmsAutomation.SEARCH_BUTTON_WITHIN)).thenReturn(List.of());
        when(input.findElement(any(By.class))).thenReturn(parent);
        when(parent.findElements(SeleniumCmsAutomation.SEARCH_BUTTON_WITHIN)).thenReturn(List.of(button));
        when(button.isDisplayed()).thenReturn(true);
        when(button.isEnabled()).thenReturn(true);
    }
}
