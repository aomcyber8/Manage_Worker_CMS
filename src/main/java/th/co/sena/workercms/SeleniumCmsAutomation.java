package th.co.sena.workercms;

import io.github.bonigarcia.wdm.WebDriverManager;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.openqa.selenium.By;
import org.openqa.selenium.ElementClickInterceptedException;
import org.openqa.selenium.Keys;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.stereotype.Service;

@Service
public class SeleniumCmsAutomation implements CmsAutomation {
    static final By USERNAME = By.cssSelector("#txt_username");
    static final By PASSWORD = By.cssSelector("#txt_password");
    static final By LOGIN = By.cssSelector("#btn_login");
    static final By SEARCH = By.cssSelector("input[type='search'], input[ng-model*='search'], input[ng-model*='Search'], input[placeholder*='Search'], input[placeholder*='search'], input.search-input, input[ng-model*='filter'], input[ng-model*='Filter']");
    static final By ROWS = By.cssSelector("table tbody tr");
    static final By STATUS = By.xpath("//*[normalize-space(text())='Employee' or normalize-space(text())='Ex-Employee']");
    static final By TOGGLE = By.cssSelector("input[type='checkbox'], [role='switch'], md-switch");
    static final By SAVE = By.xpath("//button[normalize-space(.)='Save' or normalize-space(.)='บันทึก' or @title='Save' or @aria-label='Save' or .//*[contains(concat(' ', normalize-space(@class), ' '), ' fa-save ')]] | //a[@title='Save' or @aria-label='Save' or .//*[contains(concat(' ', normalize-space(@class), ' '), ' fa-save ')]]");
    static final By SAVE_SUCCESS = By.xpath("//*[normalize-space(text())='SAVED' or normalize-space(text())='Saved' or normalize-space(text())='บันทึกสำเร็จ']");
    static final List<By> SEARCH_BUTTONS = List.of(
        By.xpath("//button[contains(text(),'Search')]"), By.cssSelector("button.search"),
        By.xpath("//i[contains(@class,'fa-search')]/parent::button"), By.xpath("//button[contains(text(),'ค้นหา')]")
    );
    static final List<By> SESSION_BUTTONS = List.of(
        By.xpath("//button[contains(text(),'Yes')]"), By.xpath("//a[contains(text(),'Yes')]"),
        By.cssSelector(".nx-confirm-button-ok, .swal2-confirm, #NotiflixConfirmButtonOk")
    );
    static final List<By> NO_RESULTS = List.of(By.xpath(
        "//*[contains(text(),'No data') or contains(text(),'ไม่พบข้อมูล') or contains(text(),'No results') or contains(text(),'No record') or contains(text(),'0 record') or contains(text(),'No matching records')]"
    ));
    static final List<By> USER_MENUS = List.of(By.cssSelector(".user-menu, .profile-dropdown, .avatar, .user-profile, .dropdown-toggle"));
    static final List<By> LOGOUT = List.of(By.xpath("//a[contains(text(),'Logout')] | //button[contains(text(),'Logout')] | //a[contains(text(),'Sign Out')] | //a[contains(text(),'Log Out')] | //span[contains(text(),'Logout')]"));

    private final EnvConfig config;
    private final NameService names;
    public SeleniumCmsAutomation(EnvConfig config, NameService names) { this.config = config; this.names = names; }

    @Override
    public Session open(CmsSite site, Consumer<String> log) {
        log.accept("CMS: " + site.name() + " — " + site.baseUrl());
        ChromeOptions options = new ChromeOptions();
        if (config.getBoolean("HEADLESS", false)) options.addArguments("--headless=new");
        options.addArguments("--no-sandbox", "--disable-dev-shm-usage", "--window-size=1920,1080",
            "--disable-gpu", "--disable-extensions", "--disable-notifications", "--log-level=3");
        options.setExperimentalOption("excludeSwitches", List.of("enable-automation"));
        WebDriver driver;
        try { WebDriverManager.chromedriver().setup(); driver = new ChromeDriver(options); }
        catch (Exception first) { driver = new ChromeDriver(options); }
        driver.manage().timeouts().implicitlyWait(Duration.ZERO);
        SeleniumSession session = new SeleniumSession(driver, site, log);
        try { session.login(); session.openEmployeePage(); return session; }
        catch (RuntimeException exception) { session.screenshot("startup_failed"); session.close(); throw exception; }
    }

    final class SeleniumSession implements Session {
        private final WebDriver driver;
        private final CmsSite site;
        private final Consumer<String> log;
        private final Duration timeout = Duration.ofSeconds(config.getInt("WAIT_TIMEOUT", 10));
        SeleniumSession(WebDriver driver, CmsSite site, Consumer<String> log) { this.driver = driver; this.site = site; this.log = log; }

        private WebDriverWait waitNow() { return new WebDriverWait(driver, timeout, Duration.ofMillis(200)); }
        private Optional<WebElement> firstNow(List<By> selectors, boolean clickable) {
            for (By by : selectors) for (WebElement element : driver.findElements(by)) {
                if (!clickable || (element.isDisplayed() && element.isEnabled())) return Optional.of(element);
            }
            return Optional.empty();
        }
        private WebElement waitAny(List<By> selectors, boolean clickable) {
            return waitNow().until(browser -> firstNow(selectors, clickable).orElse(null));
        }
        private WebElement waitAny(By selector, boolean clickable) { return waitAny(List.of(selector), clickable); }
        private void click(WebElement element) {
            ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", element);
            try { element.click(); }
            catch (ElementClickInterceptedException exception) { ((JavascriptExecutor) driver).executeScript("arguments[0].click();", element); }
        }

        void login() {
            log.accept("Step 1: Open URL and Login"); driver.get(site.baseUrl());
            WebElement username = waitAny(USERNAME, false); WebElement password = waitAny(PASSWORD, false);
            username.clear(); username.sendKeys(config.get("CMS_USERNAME", ""));
            password.clear(); password.sendKeys(config.get("CMS_PASSWORD", ""));
            click(waitAny(LOGIN, true));
            String event = waitNow().until(browser -> {
                if (firstNow(SESSION_BUTTONS, true).isPresent()) return "session";
                if (driver.findElements(USERNAME).isEmpty() && !driver.getCurrentUrl().toLowerCase().contains("login")) return "success";
                return null;
            });
            if ("session".equals(event)) {
                log.accept("Session conflict popup detected - clicking Yes");
                click(firstNow(SESSION_BUTTONS, true).orElseThrow());
                waitNow().until(browser -> driver.findElements(USERNAME).isEmpty() && !driver.getCurrentUrl().toLowerCase().contains("login"));
            }
            log.accept("Login OK - URL: " + driver.getCurrentUrl());
        }

        void openEmployeePage() {
            log.accept("Step 2: Open Employee page — " + site.employeeUrl()); driver.get(site.employeeUrl());
            URI current = URI.create(driver.getCurrentUrl()); URI expected = URI.create(site.employeeUrl());
            String path = Optional.ofNullable(current.getPath()).orElse("").replaceFirst("/$", "");
            if (!expected.getHost().equalsIgnoreCase(current.getHost())) throw new IllegalStateException("เปิดหน้า Employee ผิดระบบ CMS");
            if (path.toLowerCase().contains("login")) throw new IllegalStateException("Session หมดอายุและถูกส่งกลับหน้า Login");
            if (!path.equalsIgnoreCase(expected.getPath()) && !path.toLowerCase().startsWith(expected.getPath().toLowerCase() + "/")) {
                throw new IllegalStateException("ระบบไม่ได้เปิดหน้า Employee ตาม URL ที่กำหนด");
            }
            waitAny(SEARCH, false); log.accept("Employee page ready: " + driver.getCurrentUrl());
        }

        @Override
        public WorkerResult process(WorkerRecord record, int index, int total, boolean dryRun) {
            log.accept("[" + index + "/" + total + "] Row " + record.row() + " | ID: " + record.id() + " | Search: " + record.searchName());
            try {
                List<WebElement> matches = search(record.searchName(), record.searchName());
                if (matches.size() > 1) throw new IllegalStateException("ต้องพบแถวที่มีชื่อตรงกับ '" + record.searchName() + "' เพียง 1 แถว (พบ " + matches.size() + " แถว)");
                String surname = names.surname(record.searchName());
                if (matches.isEmpty() && !surname.isBlank()) {
                    log.accept("ไม่พบจากชื่อ ลองค้นหาด้วยนามสกุล: " + surname);
                    matches = search(surname, record.searchName());
                    if (matches.size() > 1) throw new IllegalStateException("ค้นหาด้วยนามสกุลแล้วพบชื่อ '" + record.searchName() + "' ซ้ำ " + matches.size() + " แถว");
                }
                if (matches.isEmpty()) {
                    String message = surname.isBlank() ? "หาไม่พบ (ชื่อไม่มีนามสกุลสำหรับค้นหาซ้ำ)" : "หาไม่พบ (ค้นหาด้วยชื่อและนามสกุลแล้ว)";
                    log.accept(message); return result(record, WorkerStatus.NOT_FOUND, message);
                }
                List<WebElement> links = matches.getFirst().findElements(By.cssSelector("td a"));
                click(links.isEmpty() ? matches.getFirst() : links.getFirst());
                waitStatus();
                String message = deactivate(dryRun);
                driver.navigate().back(); waitAny(SEARCH, false);
                return result(record, dryRun ? WorkerStatus.SKIPPED : WorkerStatus.SUCCESS, message);
            } catch (Exception exception) {
                String message = (exception instanceof TimeoutException ? "Timeout: " : "Error: ") + safeMessage(exception);
                log.accept(message); screenshot(record.code()); recoverList();
                return result(record, WorkerStatus.FAILED, message);
            }
        }

        private List<WebElement> search(String term, String targetName) {
            List<String> previous = rowSignature(visibleRows());
            WebElement input = waitAny(SEARCH, false); input.clear(); input.sendKeys(term);
            Optional<WebElement> button = firstNow(SEARCH_BUTTONS, true);
            if (button.isPresent()) click(button.get()); else input.sendKeys(Keys.RETURN);
            log.accept("Search: " + term);
            List<WebElement> refreshed = waitNow().until(browser -> {
                List<WebElement> rows = visibleRows();
                if (firstNow(NO_RESULTS, false).filter(WebElement::isDisplayed).isPresent()) return rows;
                return rowSignature(rows).equals(previous) ? null : rows;
            });
            String key = names.key(targetName);
            return refreshed.stream().filter(row -> row.findElements(By.cssSelector("td")).stream()
                .anyMatch(cell -> names.key(cell.getText()).equals(key))).toList();
        }
        private List<WebElement> visibleRows() { return driver.findElements(ROWS).stream().filter(WebElement::isDisplayed).toList(); }
        private List<String> rowSignature(List<WebElement> rows) { return rows.stream().map(row -> row.getText().strip().replaceAll("\\s+", " ")).toList(); }

        private WebElement waitStatus() {
            return waitNow().until(browser -> {
                List<WebElement> labels = driver.findElements(STATUS).stream().filter(WebElement::isDisplayed).toList();
                return labels.size() == 1 ? labels.getFirst() : null;
            });
        }
        private WebElement status() {
            List<WebElement> labels = driver.findElements(STATUS).stream().filter(WebElement::isDisplayed).toList();
            if (labels.size() != 1) throw new IllegalStateException("ไม่พบสถานะ Employee / Ex-Employee ที่ชัดเจนเพียงจุดเดียว");
            return labels.getFirst();
        }
        private WebElement toggle() {
            WebElement label = status(); WebElement container = label;
            for (int level = 0; level < 4; level++) {
                List<WebElement> controls = container.findElements(TOGGLE);
                if (controls.size() == 1) {
                    WebElement control = controls.getFirst();
                    if (!control.isEnabled()) throw new IllegalStateException("สวิตช์ Status ไม่พร้อมใช้งาน");
                    if (control.isDisplayed()) return control;
                    List<WebElement> labels = control.findElements(By.xpath("ancestor::label[1]"));
                    if (labels.size() == 1 && labels.getFirst().isDisplayed()) return labels.getFirst();
                }
                container = container.findElement(By.xpath(".."));
            }
            throw new IllegalStateException("ไม่พบสวิตช์ Status ที่ระบุได้แน่นอน กรุณาตรวจ selector ของ CMS");
        }
        private WebElement saveButton() {
            List<WebElement> buttons = driver.findElements(SAVE).stream().filter(e -> e.isDisplayed() && e.isEnabled()).toList();
            if (buttons.size() != 1) throw new IllegalStateException("ไม่พบปุ่มบันทึกหลักเพียงปุ่มเดียว กรุณาตรวจ selector ของ CMS");
            return buttons.getFirst();
        }
        private String deactivate(boolean dryRun) {
            String current = status().getText().strip();
            if ("Ex-Employee".equals(current)) return dryRun ? "ทดสอบผ่าน — เป็น Ex-Employee อยู่แล้ว" : "เป็น Ex-Employee อยู่แล้ว";
            WebElement toggle = toggle(); saveButton();
            if (dryRun) return "ทดสอบผ่าน — พบสวิตช์ Status และปุ่มบันทึก (ยังไม่เปลี่ยนสถานะ)";
            String detailUrl = driver.getCurrentUrl(); click(toggle);
            waitNow().until(browser -> "Ex-Employee".equals(status().getText().strip()));
            click(saveButton()); waitNow().until(browser -> driver.findElements(SAVE_SUCCESS).stream().anyMatch(WebElement::isDisplayed));
            driver.get(detailUrl); waitStatus();
            if (!"Ex-Employee".equals(status().getText().strip())) throw new IllegalStateException("บันทึกไม่สำเร็จ: เปิดหน้าใหม่แล้วยังไม่เป็น Ex-Employee");
            return "ปิดสิทธิ์ใช้งานสำเร็จ — Ex-Employee";
        }
        private WorkerResult result(WorkerRecord record, WorkerStatus status, String message) {
            return new WorkerResult(record.row(), record.code(), record.name(), record.searchName(), status, message);
        }
        private void recoverList() {
            try { if (driver.findElements(SEARCH).isEmpty()) { driver.navigate().back(); waitAny(SEARCH, false); } }
            catch (Exception ignored) { }
        }
        private String safeMessage(Exception exception) {
            String message = Optional.ofNullable(exception.getMessage()).orElse(exception.getClass().getSimpleName());
            return message.length() > 160 ? message.substring(0, 160) : message;
        }
        void screenshot(String code) {
            try {
                Path directory = Path.of("logs", "screenshots"); Files.createDirectories(directory);
                String safe = code.replaceAll("[^\\p{L}\\p{N}_.-]+", "_");
                Path file = directory.resolve("error_" + safe + "_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".png");
                Files.write(file, ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES));
            } catch (Exception ignored) { }
        }
        @Override public void logout() {
            try {
                firstNow(USER_MENUS, true).ifPresent(this::click);
                click(waitAny(LOGOUT, true));
                waitNow().until(browser -> !driver.findElements(USERNAME).isEmpty() || driver.getCurrentUrl().toLowerCase().contains("login"));
                log.accept("Logout สำเร็จ");
            } catch (Exception exception) { log.accept("ไม่พบปุ่ม Logout - ปิด browser โดยตรง"); }
        }
        @Override public void close() { try { driver.quit(); } catch (Exception ignored) { } }
    }
}
