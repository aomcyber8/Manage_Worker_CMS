package th.co.sena.workercms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.WebDriver;
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
    void searchesByNormalizedNameAndDryRunVerifiesControls() {
        ChromeDriver driver = mock(ChromeDriver.class);
        WebElement input = mock(WebElement.class), row = mock(WebElement.class), cell = mock(WebElement.class);
        WebElement link = mock(WebElement.class), label = mock(WebElement.class), toggle = mock(WebElement.class), save = mock(WebElement.class);
        when(driver.findElements(SeleniumCmsAutomation.SEARCH)).thenReturn(List.of(input));
        when(driver.navigate()).thenReturn(mock(WebDriver.Navigation.class));
        when(driver.findElements(SeleniumCmsAutomation.ROWS)).thenReturn(List.of(), List.of(row));
        for (By selector : SeleniumCmsAutomation.SEARCH_BUTTONS) when(driver.findElements(selector)).thenReturn(List.of());
        for (By selector : SeleniumCmsAutomation.NO_RESULTS) when(driver.findElements(selector)).thenReturn(List.of());
        when(row.isDisplayed()).thenReturn(true); when(row.getText()).thenReturn("นาย สมชาย ใจดี");
        when(row.findElements(By.cssSelector("td"))).thenReturn(List.of(cell)); when(cell.getText()).thenReturn("นาย สมชาย ใจดี");
        when(row.findElements(By.cssSelector("td a"))).thenReturn(List.of(link));
        when(driver.findElements(SeleniumCmsAutomation.STATUS)).thenReturn(List.of(label));
        when(label.isDisplayed()).thenReturn(true); when(label.getText()).thenReturn("Employee");
        when(label.findElements(SeleniumCmsAutomation.TOGGLE)).thenReturn(List.of(toggle));
        when(toggle.isEnabled()).thenReturn(true); when(toggle.isDisplayed()).thenReturn(true);
        when(driver.findElements(SeleniumCmsAutomation.SAVE)).thenReturn(List.of(save));
        when(save.isDisplayed()).thenReturn(true); when(save.isEnabled()).thenReturn(true);

        var session = new SeleniumCmsAutomation(config, names).new SeleniumSession(driver, CmsSite.SENA, message -> {});
        WorkerRecord record = new WorkerRecord(2, "EMP001", "สมชาย ใจดี", "", "EMP001", "", "นาย สมชาย ใจดี", "", "", "");
        WorkerResult result = session.process(record, 1, 1, true);

        assertEquals(WorkerStatus.SKIPPED, result.status(), result.message());
        verify(input).sendKeys("สมชาย ใจดี");
    }
}
