package th.co.sena.workercms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.Alert;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.NoAlertPresentException;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;

class SeleniumCmsAutomationTest {
    private final EnvConfig config = new EnvConfig(Map.of("WAIT_TIMEOUT", "1", "STATUS_SETTLE_MILLIS", "0"));
    private final NameService names = new NameService();

    @Test
    void rejectsEmployeePageOnWrongDomain() {
        ChromeDriver driver = mock(ChromeDriver.class);
        when(driver.getCurrentUrl()).thenReturn("https://senx.on.lk/Common/Employee");
        var session = session(driver);
        assertThrows(IllegalStateException.class, session::openEmployeePage);
    }

    @Test
    void reportsCmsAccessDeniedWithAccessId() {
        ChromeDriver driver = mock(ChromeDriver.class);
        when(driver.getCurrentUrl()).thenReturn("https://sena.on.lk/Error/AccessDenied");
        when(driver.getPageSource()).thenReturn("You are not authorized to view this page. Role is not defined. Access Id : 89");

        var session = session(driver);
        IllegalStateException error = assertThrows(IllegalStateException.class, session::openEmployeePage);

        assertTrue(error.getMessage().contains("ไม่มีสิทธิ์"));
        assertTrue(error.getMessage().contains("Access Id 89"));
        verify(driver, times(3)).get("https://sena.on.lk/Common/Employee");
    }

    @Test
    void reloadsTransientAccessDeniedPageAndContinuesWhenEmployeePageBecomesReady() {
        ChromeDriver driver = mock(ChromeDriver.class);
        WebElement search = mock(WebElement.class), parent = mock(WebElement.class), button = mock(WebElement.class);
        pairSearch(search, parent, button);
        when(driver.getCurrentUrl()).thenReturn(
            "https://sena.on.lk/Error/AccessDenied",
            "https://sena.on.lk/Common/Employee",
            "https://sena.on.lk/Common/Employee"
        );
        when(driver.getPageSource()).thenReturn(
            "You are not authorized to view this page. Role is not defined. Access Id : 89",
            ""
        );
        when(driver.findElements(SeleniumCmsAutomation.EMPLOYEE_SEARCH_INPUTS)).thenReturn(List.of(search));

        session(driver).openEmployeePage();

        verify(driver, times(2)).get("https://sena.on.lk/Common/Employee");
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

        session(driver).openEmployeePage();

        verify(driver).get("https://sena.on.lk/Common/Employee");
    }

    @Test
    void dryRunStopsAtMatchingGridRowWithoutOpeningDetailOrReadingStatus() {
        ChromeDriver driver = mock(ChromeDriver.class);
        WebElement decoy = mock(WebElement.class), input = mock(WebElement.class), parent = mock(WebElement.class);
        WebElement row = mock(WebElement.class), cell = mock(WebElement.class), link = mock(WebElement.class);
        WebElement searchButton = mock(WebElement.class);
        when(decoy.getAttribute("placeholder")).thenReturn("Search");
        when(decoy.isDisplayed()).thenReturn(true);
        when(decoy.isEnabled()).thenReturn(true);
        when(decoy.findElements(SeleniumCmsAutomation.SEARCH_BUTTON_WITHIN)).thenReturn(List.of());
        when(decoy.findElement(any(By.class))).thenThrow(new NoSuchElementException("no paired button"));
        pairSearch(input, parent, searchButton);
        when(driver.findElements(SeleniumCmsAutomation.EMPLOYEE_SEARCH_INPUTS)).thenReturn(List.of(decoy, input));
        when(driver.findElements(SeleniumCmsAutomation.GRID_ROWS)).thenReturn(List.of(), List.of(row));
        for (By selector : SeleniumCmsAutomation.NO_RESULTS) when(driver.findElements(selector)).thenReturn(List.of());
        matchingRow(row, cell, link);

        WorkerResult result = session(driver).process(worker(), 1, 1, true);

        assertEquals(WorkerStatus.SKIPPED, result.status());
        assertEquals("พบข้อมูล — ชื่อตรงหนึ่งรายการ", result.message());
        verify(input).sendKeys("สมชาย ใจดี");
        verify(decoy, never()).sendKeys("สมชาย ใจดี");
        verify(searchButton).click();
        verify(link, never()).click();
        verify(driver, never()).findElements(SeleniumCmsAutomation.STATUS_SWITCH_LABELS);
    }

    @Test
    void opensNewDetailTabUsesStructuralStatusAndReturnsThroughRealEmployeeLink() {
        ChromeDriver driver = mock(ChromeDriver.class);
        DetailScenario page = detailScenario(driver, false, true);
        WebElement message = domSaveSuccess(driver);

        WorkerResult result = session(driver).process(worker(), 1, 1, false);

        assertEquals(WorkerStatus.SUCCESS, result.status());
        assertTrue(result.message().contains("Ex-Employee"));
        verify(page.nameLink()).click();
        verify(page.statusLabel()).click();
        verify(page.save()).click();
        verify(message.findElements(SeleniumCmsAutomation.SAVE_SUCCESS_OK).getFirst()).click();
        verify(page.employeeList()).click();
        assertTrue(!page.sourceOpen().get());
        assertEquals("detail", page.currentHandle().get());
    }

    @Test
    void supportsDetailOpeningInTheSameTab() {
        ChromeDriver driver = mock(ChromeDriver.class);
        DetailScenario page = detailScenario(driver, true, false);

        WorkerResult result = session(driver).process(worker(), 1, 1, false);

        assertEquals(WorkerStatus.SUCCESS, result.status());
        verify(page.employeeList()).click();
        verify(driver, never()).close();
    }

    @Test
    void checkedSwitchInputTakesPrecedenceOverMisleadingToggleText() {
        ChromeDriver driver = mock(ChromeDriver.class);
        DetailScenario page = detailScenario(driver, false, true);
        when(page.statusLabel().getText()).thenAnswer(invocation -> page.exEmployee().get() ? "Ex-Employee" : "Employee");
        when(page.on().isDisplayed()).thenReturn(false);
        when(page.off().isDisplayed()).thenReturn(true);
        domSaveSuccess(driver);

        WorkerResult result = session(driver).process(worker(), 1, 1, false);

        assertEquals(WorkerStatus.SUCCESS, result.status());
        verify(page.statusLabel()).click();
        verify(page.save()).click();
    }
    @Test
    void statusInputCanBeLinkedThroughLabelForAndInputId() {
        ChromeDriver driver = mock(ChromeDriver.class);
        DetailScenario page = detailScenario(driver, false, true);
        when(page.statusLabel().findElements(SeleniumCmsAutomation.STATUS_INPUT_NEAR_LABEL)).thenReturn(List.of());
        when(page.statusLabel().getAttribute("for")).thenReturn("employee-status");
        when(driver.findElements(By.id("employee-status"))).thenReturn(List.of(page.statusInput()));
        domSaveSuccess(driver);

        WorkerResult result = session(driver).process(worker(), 1, 1, false);

        assertEquals(WorkerStatus.SUCCESS, result.status());
        verify(page.statusLabel()).click();
        verify(page.save()).click();
    }

    @Test
    void ignoresOtherSwitchInputsOnEmployeeDetail() {
        ChromeDriver driver = mock(ChromeDriver.class);
        DetailScenario page = detailScenario(driver, false, true);
        WebElement emailLabel = mock(WebElement.class);
        when(emailLabel.isDisplayed()).thenReturn(true);
        when(driver.findElements(SeleniumCmsAutomation.STATUS_SWITCH_LABELS))
            .thenReturn(List.of(emailLabel, page.statusLabel()));
        domSaveSuccess(driver);

        WorkerResult result = session(driver).process(worker(), 1, 1, false);

        assertEquals(WorkerStatus.SUCCESS, result.status());
        verify(emailLabel, never()).click();
        verify(page.statusLabel()).click();
    }
    @Test
    void saveDocumentCanBeIdentifiedByAriaLabel() {
        ChromeDriver driver = mock(ChromeDriver.class);
        DetailScenario page = detailScenario(driver, false, true);
        when(page.save().getAttribute("title")).thenReturn("");
        when(page.save().getAttribute("aria-label")).thenReturn("Save Document");
        domSaveSuccess(driver);

        WorkerResult result = session(driver).process(worker(), 1, 1, false);

        assertEquals(WorkerStatus.SUCCESS, result.status());
        verify(page.save()).click();
    }

    @Test
    void saveDocumentCanBeIdentifiedByOriginalTitle() {
        ChromeDriver driver = mock(ChromeDriver.class);
        DetailScenario page = detailScenario(driver, false, true);
        when(page.save().getAttribute("title")).thenReturn("");
        when(page.save().getAttribute("data-original-title")).thenReturn("Save Document");
        domSaveSuccess(driver);

        WorkerResult result = session(driver).process(worker(), 1, 1, false);

        assertEquals(WorkerStatus.SUCCESS, result.status());
        verify(page.save()).click();
    }

    @Test
    void ignoresSavedBannerAndOtherToolbarControls() {
        ChromeDriver driver = mock(ChromeDriver.class);
        DetailScenario page = detailScenario(driver, false, true);
        WebElement saved = mock(WebElement.class), print = mock(WebElement.class);
        when(saved.isDisplayed()).thenReturn(true); when(saved.isEnabled()).thenReturn(true);
        when(saved.getAttribute("title")).thenReturn("SAVED");
        when(print.isDisplayed()).thenReturn(true); when(print.isEnabled()).thenReturn(true);
        when(print.getAttribute("title")).thenReturn("Print Document");
        when(driver.findElements(SeleniumCmsAutomation.SAVE)).thenReturn(List.of(saved, print, page.save()));
        domSaveSuccess(driver);

        WorkerResult result = session(driver).process(worker(), 1, 1, false);

        assertEquals(WorkerStatus.SUCCESS, result.status());
        verify(page.save()).click();
        verify(saved, never()).click();
        verify(print, never()).click();
    }
    @Test
    void employeeBreadcrumbDoesNotCountAsStatusSwitch() {
        ChromeDriver driver = mock(ChromeDriver.class);
        DetailScenario page = detailScenario(driver, true, true);
        WebElement breadcrumb = mock(WebElement.class);
        when(breadcrumb.isDisplayed()).thenReturn(true);
        when(breadcrumb.getText()).thenReturn("Employee");

        WorkerResult result = session(driver).process(worker(), 1, 1, false);

        assertEquals(WorkerStatus.SUCCESS, result.status());


        verify(breadcrumb, never()).click();
    }

    @Test
    void deactivateAcceptsNativeSaveSuccessAlert() {
        ChromeDriver driver = mock(ChromeDriver.class);
        DetailScenario page = detailScenario(driver, false, true);
        Alert alert = mock(Alert.class);
        when(alert.getText()).thenReturn("Save Data Successful");
        page.alert().set(alert);

        WorkerResult result = session(driver).process(worker(), 1, 1, false);

        assertEquals(WorkerStatus.SUCCESS, result.status());
        verify(alert).accept();
        verify(page.employeeList()).click();
    }

    @Test
    void alreadyExEmployeeSkipsSaveAndReturnsThroughEmployeeList() {
        ChromeDriver driver = mock(ChromeDriver.class);
        DetailScenario page = detailScenario(driver, true, true);

        WorkerResult result = session(driver).process(worker(), 1, 1, false);

        assertEquals(WorkerStatus.SUCCESS, result.status());
        assertTrue(result.message().contains("เป็นสถานะนี้อยู่แล้ว"));
        verify(page.statusLabel(), never()).click();
        verify(page.save(), never()).click();
        verify(page.employeeList()).click();
    }

    @Test
    void missingSaveDocumentFailsBeforeChangingStatus() {
        ChromeDriver driver = mock(ChromeDriver.class);
        DetailScenario page = detailScenario(driver, false, true);
        when(driver.findElements(SeleniumCmsAutomation.SAVE)).thenReturn(List.of());

        WorkerResult result = session(driver).process(worker(), 1, 1, false);

        assertEquals(WorkerStatus.FAILED, result.status());
        assertTrue(result.message().contains("Save Document"));
        verify(page.statusLabel(), never()).click();
    }

    @Test
    void ambiguousSaveDocumentFailsBeforeChangingStatus() {
        ChromeDriver driver = mock(ChromeDriver.class);
        DetailScenario page = detailScenario(driver, false, true);
        WebElement anotherSave = mock(WebElement.class);
        when(anotherSave.isDisplayed()).thenReturn(true);
        when(anotherSave.isEnabled()).thenReturn(true);
        when(anotherSave.getAttribute("title")).thenReturn("Save Document");
        when(driver.findElements(SeleniumCmsAutomation.SAVE)).thenReturn(List.of(page.save(), anotherSave));

        WorkerResult result = session(driver).process(worker(), 1, 1, false);

        assertEquals(WorkerStatus.FAILED, result.status());
        assertTrue(result.message().contains("พบ 2 ปุ่ม"));
        verify(page.statusLabel(), never()).click();
        verify(page.save(), never()).click();
        verify(anotherSave, never()).click();
    }
    @Test
    void missingSaveSuccessPopupFailsTheWorker() {
        ChromeDriver driver = mock(ChromeDriver.class);
        DetailScenario page = detailScenario(driver, false, true);
        when(driver.findElements(SeleniumCmsAutomation.SAVE_SUCCESS)).thenReturn(List.of());

        WorkerResult result = session(driver).process(worker(), 1, 1, false);

        assertEquals(WorkerStatus.FAILED, result.status());
        verify(page.save()).click();
        verify(page.employeeList(), never()).click();
    }

    @Test
    void missingEmployeeListLinkFailsTheWorker() {
        ChromeDriver driver = mock(ChromeDriver.class);
        DetailScenario page = detailScenario(driver, true, true);
        when(driver.findElements(SeleniumCmsAutomation.EMPLOYEE_LIST_LINK)).thenReturn(List.of());

        WorkerResult result = session(driver).process(worker(), 1, 1, false);

        assertEquals(WorkerStatus.FAILED, result.status());
        verify(page.save(), never()).click();
    }

    @Test
    void ambiguousStatusSwitchFailsTheWorker() {
        ChromeDriver driver = mock(ChromeDriver.class);
        DetailScenario page = detailScenario(driver, true, true);
        WebElement secondLabel = mock(WebElement.class), secondInput = mock(WebElement.class);
        WebElement secondOn = mock(WebElement.class), secondOff = mock(WebElement.class);
        when(secondLabel.isDisplayed()).thenReturn(true);
        when(secondLabel.findElements(SeleniumCmsAutomation.STATUS_ON)).thenReturn(List.of(secondOn));
        when(secondLabel.findElements(SeleniumCmsAutomation.STATUS_OFF)).thenReturn(List.of(secondOff));
        when(secondLabel.findElements(SeleniumCmsAutomation.STATUS_INPUT_NEAR_LABEL)).thenReturn(List.of(secondInput));
        when(secondInput.getAttribute("class")).thenReturn("switch-input");
        when(driver.findElements(SeleniumCmsAutomation.STATUS_SWITCH_LABELS))
            .thenReturn(List.of(page.statusLabel(), secondLabel));

        WorkerResult result = session(driver).process(worker(), 1, 1, false);

        assertEquals(WorkerStatus.FAILED, result.status());
        assertTrue(result.message().contains("มากกว่าหนึ่งจุด"), result.message());
    }

    @Test
    void emptyTableFallsBackToSurnameAndReturnsNotFound() {
        WebDriver driver = mock(WebDriver.class);
        WebElement input = mock(WebElement.class), parent = mock(WebElement.class), button = mock(WebElement.class);
        pairSearch(input, parent, button);
        when(driver.findElements(SeleniumCmsAutomation.EMPLOYEE_SEARCH_INPUTS)).thenReturn(List.of(input));
        when(driver.findElements(SeleniumCmsAutomation.GRID_ROWS)).thenReturn(List.of());
        for (By selector : SeleniumCmsAutomation.NO_RESULTS) when(driver.findElements(selector)).thenReturn(List.of());

        WorkerResult result = new SeleniumCmsAutomation(config, names)
            .new SeleniumSession(driver, CmsSite.SENA, message -> {}).process(worker(), 1, 1, true);

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

        WorkerResult result = new SeleniumCmsAutomation(config, names)
            .new SeleniumSession(driver, CmsSite.SENA, message -> {}).process(worker(), 1, 1, true);

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

        WorkerResult result = new SeleniumCmsAutomation(config, names)
            .new SeleniumSession(driver, CmsSite.SENA, message -> {}).process(worker(), 1, 1, true);

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

        WorkerResult result = new SeleniumCmsAutomation(config, names)
            .new SeleniumSession(driver, CmsSite.SENA, message -> {}).process(worker(), 1, 1, true);

        assertEquals(WorkerStatus.FAILED, result.status());
        assertTrue(result.message().contains("มากกว่าหนึ่งชุด"), result.message());
    }

    private SeleniumCmsAutomation.SeleniumSession session(ChromeDriver driver) {
        return new SeleniumCmsAutomation(config, names).new SeleniumSession(driver, CmsSite.SENA, message -> {});
    }

    private WorkerRecord worker() {
        return new WorkerRecord(2, "EMP001", "สมชาย ใจดี", "", "EMP001", "",
            "นาย สมชาย ใจดี", "", "", "");
    }

    private void matchingRow(WebElement row, WebElement cell, WebElement link) {
        when(row.isDisplayed()).thenReturn(true);
        when(row.getText()).thenReturn("นาย สมชาย ใจดี");
        when(row.findElements(SeleniumCmsAutomation.RESULT_CELLS)).thenReturn(List.of(cell));
        when(cell.isDisplayed()).thenReturn(true);
        when(cell.getText()).thenReturn("นาย สมชาย ใจดี");
        when(cell.findElements(SeleniumCmsAutomation.RESULT_LINKS)).thenReturn(List.of(link));
        when(link.isDisplayed()).thenReturn(true);
        when(link.getText()).thenReturn("นาย สมชาย ใจดี");
    }

    private WebElement domSaveSuccess(ChromeDriver driver) {
        WebElement message = mock(WebElement.class), ok = mock(WebElement.class);
        when(message.isDisplayed()).thenReturn(true);
        when(message.findElements(SeleniumCmsAutomation.SAVE_SUCCESS_OK)).thenReturn(List.of(ok));
        when(ok.isDisplayed()).thenReturn(true);
        when(ok.isEnabled()).thenReturn(true);
        when(driver.findElements(SeleniumCmsAutomation.SAVE_SUCCESS)).thenReturn(List.of(message));
        return message;
    }

    private DetailScenario detailScenario(ChromeDriver driver, boolean alreadyExEmployee, boolean newTab) {
        WebElement input = mock(WebElement.class), parent = mock(WebElement.class), searchButton = mock(WebElement.class);
        WebElement row = mock(WebElement.class), cell = mock(WebElement.class), nameLink = mock(WebElement.class);
        WebElement statusLabel = mock(WebElement.class), statusInput = mock(WebElement.class), on = mock(WebElement.class), off = mock(WebElement.class);
        WebElement save = mock(WebElement.class), employeeList = mock(WebElement.class);
        AtomicBoolean opened = new AtomicBoolean();
        AtomicBoolean sourceOpen = new AtomicBoolean(true);
        AtomicBoolean detailAtList = new AtomicBoolean();
        AtomicBoolean exEmployee = new AtomicBoolean(alreadyExEmployee);
        AtomicReference<String> currentHandle = new AtomicReference<>("list");
        AtomicReference<Alert> alert = new AtomicReference<>();

        pairSearch(input, parent, searchButton);
        when(driver.findElements(SeleniumCmsAutomation.EMPLOYEE_SEARCH_INPUTS)).thenReturn(List.of(input));
        when(driver.findElements(SeleniumCmsAutomation.GRID_ROWS)).thenReturn(List.of(), List.of(row));
        for (By selector : SeleniumCmsAutomation.NO_RESULTS) when(driver.findElements(selector)).thenReturn(List.of());
        matchingRow(row, cell, nameLink);
        doAnswer(invocation -> { opened.set(true); return null; }).when(nameLink).click();

        when(driver.getWindowHandle()).thenAnswer(invocation -> currentHandle.get());
        when(driver.getWindowHandles()).thenAnswer(invocation -> {
            Set<String> handles = new LinkedHashSet<>();
            if (sourceOpen.get()) handles.add("list");
            if (newTab && opened.get()) handles.add("detail");

            return handles;
        });
        WebDriver.TargetLocator target = mock(WebDriver.TargetLocator.class);
        when(driver.switchTo()).thenReturn(target);
        when(target.window(any(String.class))).thenAnswer(invocation -> {
            currentHandle.set(invocation.getArgument(0));
            return driver;
        });
        when(target.alert()).thenAnswer(invocation -> {
            if (alert.get() == null) throw new NoAlertPresentException();
            return alert.get();
        });
        doAnswer(invocation -> {
            if ("list".equals(currentHandle.get())) sourceOpen.set(false);
            return null;
        }).when(driver).close();
        when(driver.getCurrentUrl()).thenAnswer(invocation -> {
            boolean onDetail = newTab ? "detail".equals(currentHandle.get()) : opened.get();
            if (onDetail && !detailAtList.get()) return "https://sena.on.lk/Common/Employee/Form?id=1";
            return "https://sena.on.lk/Common/Employee";
        });

        when(driver.findElements(SeleniumCmsAutomation.STATUS_SWITCH_LABELS)).thenReturn(List.of(statusLabel));
        when(statusLabel.isDisplayed()).thenReturn(true);
        when(statusLabel.isEnabled()).thenReturn(true);
        when(statusLabel.findElements(SeleniumCmsAutomation.STATUS_ON)).thenReturn(List.of(on));
        when(statusLabel.findElements(SeleniumCmsAutomation.STATUS_OFF)).thenReturn(List.of(off));
        when(statusLabel.findElements(SeleniumCmsAutomation.STATUS_INPUT_NEAR_LABEL)).thenReturn(List.of(statusInput));
        when(statusLabel.getText()).thenAnswer(invocation -> exEmployee.get() ? "Ex-Employee" : "Employee");
        when(statusInput.getAttribute("class")).thenReturn("switch-input");
        when(statusInput.isEnabled()).thenReturn(true);
        when(statusInput.isSelected()).thenAnswer(invocation -> !exEmployee.get());
        when(on.isDisplayed()).thenAnswer(invocation -> !exEmployee.get());
        when(off.isDisplayed()).thenAnswer(invocation -> exEmployee.get());
        doAnswer(invocation -> { exEmployee.set(true); return null; }).when(statusLabel).click();

        when(driver.findElements(SeleniumCmsAutomation.SAVE)).thenReturn(List.of(save));
        when(save.isDisplayed()).thenReturn(true);
        when(save.isEnabled()).thenReturn(true);
        when(save.getTagName()).thenReturn("a");
        when(save.getAttribute("title")).thenReturn("Save Document");
        when(driver.findElements(SeleniumCmsAutomation.EMPLOYEE_LIST_LINK)).thenReturn(List.of(employeeList));
        when(employeeList.isDisplayed()).thenReturn(true);
        when(employeeList.isEnabled()).thenReturn(true);
        doAnswer(invocation -> { detailAtList.set(true); return null; }).when(employeeList).click();

        return new DetailScenario(nameLink, statusLabel, statusInput, on, off, save, employeeList, sourceOpen, currentHandle, alert, exEmployee);
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

    private record DetailScenario(
        WebElement nameLink,
        WebElement statusLabel,
        WebElement statusInput,
        WebElement on,
        WebElement off,
        WebElement save,
        WebElement employeeList,
        AtomicBoolean sourceOpen,
        AtomicReference<String> currentHandle,
        AtomicReference<Alert> alert,
        AtomicBoolean exEmployee
    ) {}
}