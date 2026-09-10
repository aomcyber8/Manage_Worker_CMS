package th.co.sena.workercms;

import io.github.bonigarcia.wdm.WebDriverManager;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.openqa.selenium.Alert;
import org.openqa.selenium.By;
import org.openqa.selenium.ElementClickInterceptedException;
import org.openqa.selenium.Keys;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoAlertPresentException;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.StaleElementReferenceException;
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
    static final By EMPLOYEE_SEARCH_INPUTS = By.cssSelector("input[placeholder='Search'], input[placeholder='search']");
    static final By SEARCH_BUTTON_WITHIN = By.xpath(".//*[self::button or self::a or @role='button'][contains(translate(@title,'SEARCH','search'),'search') or contains(translate(@aria-label,'SEARCH','search'),'search') or contains(concat(' ', normalize-space(@class), ' '), ' search ') or .//*[contains(concat(' ', normalize-space(@class), ' '), ' fa-search ') or contains(concat(' ', normalize-space(@class), ' '), ' glyphicon-search ') or contains(concat(' ', normalize-space(@class), ' '), ' icon-search ') or contains(concat(' ', normalize-space(@class), ' '), ' k-i-search ')]]");
    static final By GRID_ROWS = By.cssSelector(".ngViewport .ngRow, .ng-viewport .ng-row, .ui-grid-viewport .ui-grid-row, .slick-viewport .slick-row, .k-grid-content tbody tr");
    static final By HTML_TABLE_ROWS = By.xpath("//table[.//th[contains(translate(normalize-space(.),'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ'),'NAME')] and .//th[contains(translate(normalize-space(.),'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ'),'POSITION')] and .//th[contains(translate(normalize-space(.),'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ'),'USERNAME')]]//tbody/tr[td]");
    static final By RESULT_CELLS = By.cssSelector("td, .ngCell, .ng-cell, .ui-grid-cell, .slick-cell");
    static final By RESULT_LINKS = By.cssSelector("a");
    static final By STATUS_SWITCH_LABELS = By.cssSelector("label.switch-label");
    static final By STATUS_ON = By.xpath(".//*[contains(concat(' ', normalize-space(@class), ' '), ' toggle--on ') and normalize-space(.)='Employee']");
    static final By STATUS_OFF = By.xpath(".//*[contains(concat(' ', normalize-space(@class), ' '), ' toggle--off ') and normalize-space(.)='Ex-Employee']");
    static final By STATUS_INPUT_NEAR_LABEL = By.xpath("./preceding-sibling::input[contains(concat(' ', normalize-space(@class), ' '), ' switch-input ')][1] | .//input[contains(concat(' ', normalize-space(@class), ' '), ' switch-input ')]");
    static final By SAVE = By.cssSelector("button, a, [role='button']");
    static final By SAVE_SUCCESS = By.xpath("//*[normalize-space(.)='Save Data Successful' or normalize-space(.)='บันทึกข้อมูลสำเร็จ']");
    static final By SAVE_SUCCESS_OK = By.xpath(".//*[self::button or self::a or @role='button'][normalize-space(.)='OK' or normalize-space(.)='ตกลง']");
    static final By EMPLOYEE_LIST_LINK = By.xpath("//a[@href='/Common/Employee' and (translate(normalize-space(@title),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='employee list' or @gotolist)]");
    static final Pattern ACCESS_ID = Pattern.compile("Access\\s*Id\\s*[:：]\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

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
        private final long searchSettleMillis = Math.max(300, config.getInt("SEARCH_SETTLE_MILLIS", 750));
        private final long statusSettleMillis = Math.max(0, config.getInt("STATUS_SETTLE_MILLIS", 1200));
        SeleniumSession(WebDriver driver, CmsSite site, Consumer<String> log) { this.driver = driver; this.site = site; this.log = log; }

        private WebDriverWait waitNow() { return waitNow(timeout); }
        private WebDriverWait waitNow(Duration duration) { return new WebDriverWait(driver, duration, Duration.ofMillis(200)); }
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

        private record SearchControl(WebElement input, WebElement button) { }
        private record SearchMatch(WebElement row, WebElement nameCell) { }
        private record SearchRead(List<WebElement> rows, List<SearchMatch> matches) { }
        private record DetailWindow(String sourceHandle, String detailHandle) { }
        private record StatusControl(WebElement input, WebElement label) { }
        private final class CmsAccessDeniedException extends IllegalStateException {
            CmsAccessDeniedException(String message) { super(message); }
        }

        private List<SearchControl> employeeSearchControls() {
            List<SearchControl> controls = new ArrayList<>();
            for (WebElement input : driver.findElements(EMPLOYEE_SEARCH_INPUTS)) {
                if (!input.isDisplayed() || !input.isEnabled()
                    || !"Search".equalsIgnoreCase(Optional.ofNullable(input.getAttribute("placeholder")).orElse("").strip())) continue;

                List<WebElement> geometric = adjacentButtons(input);
                if (!geometric.isEmpty()) {
                    controls.add(new SearchControl(input, geometric.getFirst()));
                    continue;
                }

                WebElement container = input;
                for (int level = 0; level < 4; level++) {
                    if (level > 0) {
                        try {
                            WebElement parent = container.findElement(By.xpath(".."));
                            if (parent == null) break;
                            container = parent;
                        } catch (RuntimeException exception) { break; }
                    }
                    List<WebElement> buttons = container.findElements(SEARCH_BUTTON_WITHIN).stream()
                        .filter(element -> element.isDisplayed() && element.isEnabled()).toList();
                    if (!buttons.isEmpty()) {
                        for (WebElement button : buttons) controls.add(new SearchControl(input, button));
                        break;
                    }
                }
            }
            return controls;
        }

        private List<WebElement> adjacentButtons(WebElement input) {
            if (!(driver instanceof JavascriptExecutor javascript)) return List.of();
            try {
                Object result = javascript.executeScript("""
                    const input = arguments[0], ir = input.getBoundingClientRect();
                    const visible = element => {
                      const r = element.getBoundingClientRect(), style = getComputedStyle(element);
                      return r.width > 0 && r.height > 0 && style.display !== 'none'
                        && style.visibility !== 'hidden' && !element.disabled;
                    };
                    const candidates = [...document.querySelectorAll(
                      'button,a,[role="button"],input[type="button"],input[type="submit"],.input-group-addon'
                    )].filter(element => {
                      if (!visible(element) || element === input) return false;
                      const r = element.getBoundingClientRect();
                      const vertical = Math.abs((r.top + r.height / 2) - (ir.top + ir.height / 2));
                      return vertical <= Math.max(12, ir.height * 0.65)
                        && r.left >= ir.right - 8 && r.left <= ir.right + 110
                        && r.width <= 120 && r.height <= Math.max(80, ir.height * 2);
                    }).sort((a, b) => {
                      const ar = a.getBoundingClientRect(), br = b.getBoundingClientRect();
                      const as = Math.abs(ar.left - ir.right)
                        + Math.abs((ar.top + ar.height / 2) - (ir.top + ir.height / 2));
                      const bs = Math.abs(br.left - ir.right)
                        + Math.abs((br.top + br.height / 2) - (ir.top + ir.height / 2));
                      return as - bs;
                    });
                    return candidates.length ? [candidates[0]] : [];
                    """, input);
                if (!(result instanceof List<?> values)) return List.of();
                return values.stream().filter(WebElement.class::isInstance)
                    .map(WebElement.class::cast).filter(element -> element.isDisplayed() && element.isEnabled()).toList();
            } catch (RuntimeException exception) {
                return List.of();
            }
        }

        private SearchControl waitEmployeeSearchControl() {
            try {
                return waitNow().until(browser -> {
                    List<SearchControl> controls = employeeSearchControls();
                    return controls.size() == 1 ? controls.getFirst() : null;
                });
            } catch (TimeoutException exception) {
                int count = employeeSearchControls().size();
                String message = count == 0
                    ? "ไม่พบช่อง Employee Search ที่จับคู่กับปุ่มแว่นขยาย"
                    : "พบชุดช่อง Employee Search และปุ่มแว่นขยายมากกว่าหนึ่งชุด (" + count + " ชุด)";
                throw new IllegalStateException(message, exception);
            }
        }

        private void click(WebElement element) {
            if (driver instanceof JavascriptExecutor javascript) {
                javascript.executeScript("arguments[0].scrollIntoView({block:'center'});", element);
            }
            try { element.click(); }
            catch (ElementClickInterceptedException exception) {
                if (driver instanceof JavascriptExecutor javascript) javascript.executeScript("arguments[0].click();", element);
                else throw exception;
            }
        }

        void login() {
            log.accept("Step 1: Open URL and Login"); driver.get(site.baseUrl());
            WebElement username = waitAny(USERNAME, false); WebElement password = waitAny(PASSWORD, false);
            username.clear(); username.sendKeys(config.get("CMS_USERNAME", ""));
            password.clear(); password.sendKeys(config.get("CMS_PASSWORD", ""));
            click(waitAny(LOGIN, true));
            String event = waitNow().until(browser -> {
                if (firstNow(SESSION_BUTTONS, true).isPresent()) return "session";
                if (loggedInPageReady()) return "success";
                return null;
            });
            if ("session".equals(event)) {
                log.accept("Session conflict popup detected - clicking Yes");
                click(firstNow(SESSION_BUTTONS, true).orElseThrow());
                waitNow().until(browser -> loggedInPageReady());
            }
            log.accept("Login OK - URL: " + driver.getCurrentUrl());
        }

        void openEmployeePage() {
            log.accept("Step 2: Open Employee page — " + site.employeeUrl());
            driver.get(site.employeeUrl());
            int maxAccessAttempts = Math.max(1, config.getInt("ACCESS_RETRY_COUNT", 3));
            for (int attempt = 1; attempt <= maxAccessAttempts; attempt++) {
                try {
                    awaitEmployeePage();
                    log.accept("Employee page ready: " + driver.getCurrentUrl());
                    return;
                } catch (CmsAccessDeniedException denied) {
                    if (attempt >= maxAccessAttempts) throw denied;
                    log.accept("CMS ตรวจสิทธิ์หน้า Employee ไม่ผ่านชั่วคราว โหลดหน้าเดิมซ้ำ (ครั้งที่ "
                        + (attempt + 1) + "/" + maxAccessAttempts + ")");
                    driver.get(site.employeeUrl());
                }
            }
        }

        private void awaitEmployeePage() {
            long firstAttemptMillis = Math.max(500, timeout.toMillis() / 3);
            try {
                waitForEmployeeReady(Duration.ofMillis(firstAttemptMillis));
            } catch (TimeoutException firstTimeout) {
                validateCmsLocation();
                log.accept("หน้า Employee ยังไม่พร้อม เปิด URL ซ้ำ — URL ปัจจุบัน: " + driver.getCurrentUrl());
                driver.get(site.employeeUrl());
                try {
                    waitForEmployeeReady(Duration.ofMillis(Math.max(500, timeout.toMillis() - firstAttemptMillis)));
                } catch (TimeoutException secondTimeout) {
                    URI current = validateCmsLocation();
                    if (isEmployeePath(current)) {
                        int searchControlCount = employeeSearchControls().size();
                        String reason = searchControlCount > 1
                            ? "พบชุดช่อง Employee Search และปุ่มแว่นขยายมากกว่าหนึ่งชุด (" + searchControlCount + " ชุด)"
                            : "ไม่พบช่อง Employee Search ที่จับคู่กับปุ่มแว่นขยาย";
                        throw new IllegalStateException("เปิดหน้า Employee แล้วแต่" + reason + " — URL: " + driver.getCurrentUrl(), secondTimeout);
                    }
                    throw new IllegalStateException("ระบบไม่ได้เปิดหน้า Employee ตาม URL ที่กำหนด — URL ปัจจุบัน: " + driver.getCurrentUrl(), secondTimeout);
                }
            }
        }

        private boolean loggedInPageReady() {
            String currentUrl = driver.getCurrentUrl();
            if (!driver.findElements(USERNAME).isEmpty() || currentUrl == null || currentUrl.toLowerCase().contains("login")) return false;
            Object readyState = ((JavascriptExecutor) driver).executeScript("return document.readyState");
            return "complete".equals(readyState);
        }

        private void waitForEmployeeReady(Duration duration) {
            waitNow(duration).until(browser -> {
                URI current = validateCmsLocation();
                return isEmployeePath(current) && employeeSearchControls().size() == 1;
            });
        }

        private URI validateCmsLocation() {
            rejectAccessDenied();
            URI current;
            try { current = URI.create(driver.getCurrentUrl()); }
            catch (RuntimeException exception) { throw new IllegalStateException("CMS ส่ง URL ที่ไม่ถูกต้อง: " + driver.getCurrentUrl(), exception); }
            URI expected = URI.create(site.employeeUrl());
            if (!expected.getHost().equalsIgnoreCase(current.getHost())) {
                throw new IllegalStateException("เปิดหน้า Employee ผิดระบบ CMS — URL ปัจจุบัน: " + driver.getCurrentUrl());
            }
            String path = Optional.ofNullable(current.getPath()).orElse("");
            if (path.toLowerCase().contains("login")) {
                throw new IllegalStateException("Session หมดอายุและถูกส่งกลับหน้า Login — URL: " + driver.getCurrentUrl());
            }
            return current;
        }

        private void rejectAccessDenied() {
            String pageSource = Optional.ofNullable(driver.getPageSource()).orElse("");
            if (!pageSource.contains("You are not authorized")
                && !pageSource.contains("you are not authorized")
                && !pageSource.contains("คุณไม่ได้รับอนุญาตให้เห็นหน้านี้")
                && !pageSource.contains("Role is not defined")) return;
            Matcher matcher = ACCESS_ID.matcher(pageSource.replaceAll("<[^>]+>", " "));
            String accessId = matcher.find() ? " Access Id " + matcher.group(1) : "";
            throw new CmsAccessDeniedException("บัญชี CMS ไม่มีสิทธิ์เปิดหน้า Employee (Role is not defined" + accessId
                + ") กรุณาให้ผู้ดูแล CMS เพิ่มสิทธิ์แล้วลองใหม่");
        }

        private boolean isEmployeePath(URI current) {
            String actualPath = Optional.ofNullable(current.getPath()).orElse("").replaceFirst("/$", "");
            String expectedPath = URI.create(site.employeeUrl()).getPath().replaceFirst("/$", "");
            return actualPath.equalsIgnoreCase(expectedPath)
                || actualPath.toLowerCase().startsWith(expectedPath.toLowerCase() + "/");
        }

        @Override
        public WorkerResult process(WorkerRecord record, int index, int total, boolean dryRun) {
            log.accept("[" + index + "/" + total + "] Row " + record.row() + " | ID: " + record.id() + " | Search: " + record.searchName());
            try {
                List<SearchMatch> matches = search(record.searchName(), record.searchName());
                if (matches.size() > 1) throw new IllegalStateException("ต้องพบแถวที่มีชื่อตรงกับ '" + record.searchName() + "' เพียง 1 แถว (พบ " + matches.size() + " แถว)");
                String surname = names.surname(record.searchName());
                if (matches.isEmpty() && !surname.isBlank()) {
                    log.accept("ไม่พบจากชื่อ ลองค้นหาด้วยนามสกุล: " + surname);
                    matches = search(surname, record.searchName());
                    if (matches.size() > 1) throw new IllegalStateException("ค้นหาด้วยนามสกุลแล้วพบชื่อ '" + record.searchName() + "' ซ้ำ " + matches.size() + " แถว");
                }
                if (matches.isEmpty()) {
                    String message = surname.isBlank() ? "ไม่พบ (ชื่อไม่มีนามสกุลสำหรับค้นหาซ้ำ)" : "ไม่พบ (ค้นหาด้วยชื่อและนามสกุลแล้ว)";
                    log.accept(message); return result(record, WorkerStatus.NOT_FOUND, message);
                }
                if (dryRun) {
                    String message = "พบข้อมูล — ชื่อตรงหนึ่งรายการ";
                    log.accept(message);
                    return result(record, WorkerStatus.SKIPPED, message);
                }
                SearchMatch match = matches.getFirst();
                List<WebElement> links = match.nameCell().findElements(RESULT_LINKS).stream()
                    .filter(WebElement::isDisplayed)
                    .filter(link -> names.key(link.getText()).equals(names.key(record.searchName())))
                    .toList();
                if (links.size() != 1) {
                    throw new IllegalStateException("พบแถวชื่อ '" + record.searchName()
                        + "' แต่ไม่พบลิงก์ชื่อที่ชัดเจนเพียงหนึ่งจุด (พบ " + links.size() + " จุด)");
                }
                DetailWindow detail = openEmployeeDetail(links.getFirst());
                String message = deactivate();
                returnToEmployeeList(detail);
                return result(record, WorkerStatus.SUCCESS, message);
            } catch (Exception exception) {
                String message = (exception instanceof TimeoutException ? "Timeout: " : "Error: ") + safeMessage(exception);
                log.accept(message); screenshot(record.code()); recoverList();
                return result(record, WorkerStatus.FAILED, message);
            }
        }

        private List<SearchMatch> search(String term, String targetName) {
            List<String> previous = rowSignature(visibleRows());
            SearchControl control = waitEmployeeSearchControl();
            WebElement input = control.input();
            input.clear();
            input.sendKeys(term);
            log.accept("Employee Search control: " + describe(control.button()));
            click(control.button());
            log.accept("Employee Search: " + term + " (submit: ปุ่มแว่นขยาย)");

            List<WebElement> refreshed;
            long primaryMillis = Math.min(timeout.toMillis(), Math.max(2_000, searchSettleMillis * 2));
            try {
                refreshed = waitForSearchResult(previous, targetName, Duration.ofMillis(primaryMillis));
            } catch (TimeoutException firstTimeout) {
                refreshed = visibleRows();
                if (previous.isEmpty() && refreshed.isEmpty()) {
                    log.accept("Employee grid remained empty after " + primaryMillis + " ms");
                } else {
                    log.accept("ปุ่มแว่นขยายยังไม่ทำให้ตารางเปลี่ยน กำลังส่งคำค้นซ้ำด้วย Enter");
                    click(input);
                    input.clear();
                    input.sendKeys(term);
                    input.sendKeys(Keys.RETURN);
                    long remainingMillis = Math.max(300, timeout.toMillis() - primaryMillis);
                    try {
                        refreshed = waitForSearchResult(previous, targetName, Duration.ofMillis(remainingMillis));
                        log.accept("Employee Search: " + term + " (submit fallback: Enter)");
                    } catch (TimeoutException secondTimeout) {
                        refreshed = visibleRows();
                        if (!previous.isEmpty() && rowSignature(refreshed).equals(previous)) {
                            throw new IllegalStateException("ตารางผลค้นหาไม่อัปเดตหลังส่งทั้งปุ่มแว่นขยายและ Enter สำหรับ '"
                                + term + "' ภายใน " + timeout.toSeconds() + " วินาที", secondTimeout);
                        }
                    }
                }
            }

            SearchRead read = readSearchGrid(targetName);
            refreshed = read.rows();
            List<SearchMatch> matches = read.matches();
            log.accept("Employee grid rows: " + refreshed.size());
            if (refreshed.isEmpty()) {
                log.accept("CMS ไม่คืนแถวผลลัพธ์สำหรับคำค้น: " + term);
            } else if (matches.isEmpty()) {
                String samples = refreshed.stream().map(this::safeNormalizedRowText).filter(text -> !text.isBlank())
                    .limit(3).collect(Collectors.joining(" | "));
                log.accept("CMS คืน " + refreshed.size() + " แถว แต่ไม่มีชื่อเต็มตรงกับ '" + targetName
                    + "'" + (samples.isBlank() ? "" : " — ตัวอย่าง: " + samples));
                screenshot("search_mismatch_" + term);
            }
            return matches;
        }

        private List<WebElement> waitForSearchResult(List<String> previous, String targetName, Duration duration) {
            String targetKey = names.key(targetName);
            long started = System.nanoTime();
            return waitNow(duration).until(browser -> {
                try {
                    List<WebElement> rows = visibleRows();
                    if (firstNow(NO_RESULTS, false).filter(WebElement::isDisplayed).isPresent()) return rows;
                    List<String> current = rowSignature(rows);
                    for (WebElement row : rows) {
                        for (WebElement cell : resultCells(row)) {
                            if (names.key(cell.getText()).equals(targetKey)) return rows;
                        }
                    }
                    if (current.equals(previous)) return null;
                    if (!rows.isEmpty()) return rows;
                    long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
                    return elapsedMillis >= searchSettleMillis * 2 ? rows : null;
                } catch (StaleElementReferenceException exception) {
                    return null;
                }
            });
        }

        private SearchRead readSearchGrid(String targetName) {
            String key = names.key(targetName);
            Duration readTimeout = Duration.ofMillis(Math.max(500, Math.min(2_000, timeout.toMillis())));
            return waitNow(readTimeout).until(browser -> {
                try {
                    List<WebElement> rows = visibleRows();
                    List<SearchMatch> matches = new ArrayList<>();
                    for (WebElement row : rows) {
                        for (WebElement cell : resultCells(row)) {
                            if (names.key(cell.getText()).equals(key)) {
                                matches.add(new SearchMatch(row, cell));
                                break;
                            }
                        }
                    }
                    return new SearchRead(rows, matches);
                } catch (StaleElementReferenceException exception) {
                    return null;
                }
            });
        }

        private String describe(WebElement element) {
            String tag;
            try { tag = element.getTagName(); } catch (RuntimeException exception) { tag = "?"; }
            String css = Optional.ofNullable(element.getAttribute("class")).orElse("").strip().replaceAll("\\s+", ".");
            String title = Optional.ofNullable(element.getAttribute("title")).orElse("").strip();
            return "<" + tag + (css.isBlank() ? "" : " class=" + css) + (title.isBlank() ? "" : " title=" + title) + ">";
        }

        private List<WebElement> visibleRows() {
            List<WebElement> rows = new ArrayList<>();
            for (By selector : List.of(GRID_ROWS, HTML_TABLE_ROWS)) {
                for (WebElement row : driver.findElements(selector)) {
                    if (row.isDisplayed() && !resultCells(row).isEmpty() && !rows.contains(row)) rows.add(row);
                }
            }
            return rows;
        }
        private List<WebElement> resultCells(WebElement row) {
            return row.findElements(RESULT_CELLS).stream().filter(WebElement::isDisplayed).toList();
        }
        private String normalizedRowText(WebElement row) { return row.getText().strip().replaceAll("\\s+", " "); }
        private String safeNormalizedRowText(WebElement row) {
            try { return normalizedRowText(row); } catch (StaleElementReferenceException exception) { return ""; }
        }
        private List<String> rowSignature(List<WebElement> rows) { return rows.stream().map(this::normalizedRowText).toList(); }

        private DetailWindow openEmployeeDetail(WebElement link) {
            String sourceHandle = driver.getWindowHandle();
            Set<String> before = new HashSet<>(driver.getWindowHandles());
            click(link);
            String detailHandle = waitNow().until(browser -> {
                Set<String> opened = new HashSet<>(browser.getWindowHandles());
                opened.removeAll(before);
                if (opened.size() > 1) throw new IllegalStateException("ลิงก์ Name เปิดหน้ารายละเอียดมากกว่าหนึ่งแท็บ");
                if (opened.size() == 1) return opened.iterator().next();
                try {
                    return isEmployeeDetailPath(URI.create(browser.getCurrentUrl())) ? browser.getWindowHandle() : null;
                } catch (RuntimeException exception) {
                    return null;
                }
            });
            if (!detailHandle.equals(driver.getWindowHandle())) driver.switchTo().window(detailHandle);
            waitForEmployeeDetail();
            waitStatusControl();
            log.accept("Employee detail ready: " + driver.getCurrentUrl());
            return new DetailWindow(sourceHandle, detailHandle);
        }

        private boolean isEmployeeDetailPath(URI current) {
            URI expected = URI.create(site.employeeUrl());
            String actualPath = Optional.ofNullable(current.getPath()).orElse("").replaceFirst("/$", "");
            String formPath = expected.getPath().replaceFirst("/$", "") + "/Form";
            return expected.getHost().equalsIgnoreCase(current.getHost())
                && (actualPath.equalsIgnoreCase(formPath)
                    || actualPath.toLowerCase().startsWith(formPath.toLowerCase() + "/"));
        }

        private void waitForEmployeeDetail() {
            try {
                waitNow().until(browser -> {
                    String url = browser.getCurrentUrl();
                    if (url == null || url.isBlank() || url.startsWith("about:")) return null;
                    URI current;
                    try { current = URI.create(url); }
                    catch (RuntimeException exception) { throw new IllegalStateException("CMS ส่ง URL รายละเอียด Employee ที่ไม่ถูกต้อง: " + url); }
                    URI expected = URI.create(site.employeeUrl());
                    if (!expected.getHost().equalsIgnoreCase(current.getHost())) {
                        throw new IllegalStateException("หน้า Employee Detail เปิดผิดระบบ CMS — URL: " + url);
                    }
                    if (Optional.ofNullable(current.getPath()).orElse("").toLowerCase().contains("login")) {
                        throw new IllegalStateException("Session หมดอายุและถูกส่งกลับหน้า Login — URL: " + url);
                    }
                    return isEmployeeDetailPath(current) ? true : null;
                });
            } catch (TimeoutException exception) {
                throw new IllegalStateException("คลิก Name แล้วแต่ไม่พบหน้า Employee Detail ภายใน "
                    + timeout.toSeconds() + " วินาที — URL: " + driver.getCurrentUrl(), exception);
            }
        }

                private StatusControl waitStatusControl() {
            try {
                return waitNow().until(browser -> {
                    List<StatusControl> controls = statusControls();
                    if (controls.size() > 1) {
                        throw new IllegalStateException("พบสวิตช์ Status Employee / Ex-Employee มากกว่าหนึ่งจุด ("
                            + controls.size() + " จุด)");
                    }
                    return controls.size() == 1 ? controls.getFirst() : null;
                });
            } catch (TimeoutException exception) {
                int count = statusControls().size();
                throw new IllegalStateException(count == 0
                    ? "ไม่พบ input.switch-input ที่ผูกกับ Status Employee / Ex-Employee ในหน้า Employee Detail"
                    : "พบสวิตช์ Status Employee / Ex-Employee มากกว่าหนึ่งจุด (" + count + " จุด)", exception);
            }
        }

        private List<WebElement> statusLabels() {
            return driver.findElements(STATUS_SWITCH_LABELS).stream()
                .filter(WebElement::isDisplayed)
                .filter(label -> label.findElements(STATUS_ON).size() == 1 && label.findElements(STATUS_OFF).size() == 1)
                .toList();
        }

        private List<StatusControl> statusControls() {
            List<StatusControl> controls = new ArrayList<>();
            for (WebElement label : statusLabels()) {
                List<WebElement> inputs = new ArrayList<>();
                String forId = Optional.ofNullable(label.getAttribute("for")).orElse("").strip();
                if (!forId.isBlank()) {
                    driver.findElements(By.id(forId)).stream()
                        .filter(this::isSwitchInput)
                        .forEach(inputs::add);
                }
                label.findElements(STATUS_INPUT_NEAR_LABEL).stream()
                    .filter(this::isSwitchInput)
                    .forEach(inputs::add);
                inputs.stream().distinct().forEach(input -> controls.add(new StatusControl(input, label)));
            }
            return controls.stream().distinct().toList();
        }

        private boolean isSwitchInput(WebElement element) {
            String css = Optional.ofNullable(element.getAttribute("class")).orElse("");
            return List.of(css.split("\s+")).contains("switch-input");
        }

        private String statusValue() {
            return waitStatusControl().input().isSelected() ? "Employee" : "Ex-Employee";
        }

        private String settledStatusValue() {
            long readyAt = System.nanoTime() + Duration.ofMillis(statusSettleMillis).toNanos();
            return waitNow().until(browser -> System.nanoTime() < readyAt ? null : statusValue());
        }

        private StatusControl statusToggle() {
            StatusControl control = waitStatusControl();
            if (!control.input().isEnabled() || !control.label().isEnabled()) {
                throw new IllegalStateException("สวิตช์ Status ไม่พร้อมใช้งาน");
            }
            return control;
        }

        private boolean exEmployeeTextVisible(StatusControl control) {
            String visibleText = Optional.ofNullable(control.label().getText()).orElse("").strip().replaceAll("\s+", " ");
            return "Ex-Employee".equals(visibleText)
                || control.label().findElements(STATUS_OFF).stream().anyMatch(WebElement::isDisplayed);
        }
private WebElement saveButton() {
            URI current = URI.create(driver.getCurrentUrl());
            if (!isEmployeeDetailPath(current)) {
                throw new IllegalStateException("ไม่สามารถค้นหาปุ่ม Save Document นอกหน้า Employee Detail — URL: "
                    + driver.getCurrentUrl());
            }
            List<WebElement> controls = driver.findElements(SAVE).stream()
                .filter(WebElement::isDisplayed)
                .filter(WebElement::isEnabled)
                .toList();
            List<WebElement> buttons = controls.stream().filter(this::isSaveDocument).distinct().toList();
            if (buttons.size() != 1) {
                String candidates = controls.stream().filter(this::looksLikeSaveControl).limit(8)
                    .map(this::describeSaveControl).collect(Collectors.joining(", "));
                throw new IllegalStateException("ต้องพบปุ่ม Save Document ที่ระบุได้แน่นอนเพียงหนึ่งปุ่ม (พบ "
                    + buttons.size() + " ปุ่ม)" + (candidates.isBlank() ? "" : " — controls: " + candidates));
            }
            return buttons.getFirst();
        }

        private boolean isSaveDocument(WebElement element) {
            return List.of("title", "aria-label", "data-original-title").stream()
                .map(attribute -> Optional.ofNullable(element.getAttribute(attribute)).orElse("").strip())
                .anyMatch("Save Document"::equalsIgnoreCase);
        }

        private boolean looksLikeSaveControl(WebElement element) {
            return List.of("title", "aria-label", "data-original-title", "class").stream()
                .map(attribute -> Optional.ofNullable(element.getAttribute(attribute)).orElse("").toLowerCase())
                .anyMatch(value -> value.contains("save"));
        }

        private String describeSaveControl(WebElement element) {
            String tag = Optional.ofNullable(element.getTagName()).orElse("?");
            return "<" + tag + " class='" + Optional.ofNullable(element.getAttribute("class")).orElse("")
                + "' title='" + Optional.ofNullable(element.getAttribute("title")).orElse("")
                + "' aria-label='" + Optional.ofNullable(element.getAttribute("aria-label")).orElse("")
                + "' data-original-title='" + Optional.ofNullable(element.getAttribute("data-original-title")).orElse("")
                + "'>";
        }

        private String deactivate() {
            String current = settledStatusValue();
            log.accept("Status after Employee Detail settled: " + current);
            if ("Ex-Employee".equals(current)) {
                log.accept("Status เป็น Ex-Employee อยู่แล้ว");
                return "Ex-Employee — เป็นสถานะนี้อยู่แล้ว";
            }
            StatusControl toggle = statusToggle();
            saveButton();
            click(toggle.label());
            waitNow().until(browser -> {
                List<StatusControl> controls = statusControls();
                if (controls.size() > 1) {
                    throw new IllegalStateException("พบสวิตช์ Status Employee / Ex-Employee มากกว่าหนึ่งจุด ("
                        + controls.size() + " จุด)");
                }
                if (controls.size() != 1) return null;
                StatusControl updated = controls.getFirst();
                return !updated.input().isSelected() && exEmployeeTextVisible(updated) ? true : null;
            });
            log.accept("Status changed: .switch-input:checked → unchecked (Ex-Employee)");
            click(saveButton());
            closeSaveSuccess();
            log.accept("Save Data Successful — ปิดหน้าต่างด้วย OK แล้ว");
            return "Ex-Employee — บันทึกสำเร็จ";
        }

        private void closeSaveSuccess() {
            waitNow().until(browser -> {
                try {
                    WebDriver.TargetLocator target = driver.switchTo();
                    if (target != null) {
                        Alert alert = target.alert();
                        if (alert != null && "Save Data Successful".equalsIgnoreCase(alert.getText().strip())) {
                            alert.accept();
                            return true;
                        }
                    }
                } catch (NoAlertPresentException ignored) {
                    // The CMS can render the same confirmation as a DOM dialog.
                }

                for (WebElement message : driver.findElements(SAVE_SUCCESS)) {
                    if (!message.isDisplayed()) continue;
                    WebElement container = message;
                    for (int level = 0; level < 6; level++) {
                        List<WebElement> buttons = container.findElements(SAVE_SUCCESS_OK).stream()
                            .filter(element -> element.isDisplayed() && element.isEnabled()).toList();
                        if (buttons.size() == 1) {
                            click(buttons.getFirst());
                            return true;
                        }
                        try { container = container.findElement(By.xpath("..")); }
                        catch (RuntimeException exception) { break; }
                    }
                }
                return null;
            });
        }

        private void returnToEmployeeList(DetailWindow detail) {
            WebElement link = waitNow().until(browser -> {
                List<WebElement> links = driver.findElements(EMPLOYEE_LIST_LINK).stream()
                    .filter(element -> element.isDisplayed() && element.isEnabled()).toList();
                if (links.size() > 1) throw new IllegalStateException("พบลิงก์กลับ Employee List มากกว่าหนึ่งจุด");
                return links.size() == 1 ? links.getFirst() : null;
            });
            click(link);
            waitForEmployeeReady(timeout);
            closeSourceWindow(detail);
            log.accept("กลับหน้า Employee List แล้ว");
        }

        private void closeSourceWindow(DetailWindow detail) {
            String currentHandle = driver.getWindowHandle();
            if (detail.sourceHandle() == null || detail.sourceHandle().equals(currentHandle)
                || !driver.getWindowHandles().contains(detail.sourceHandle())) return;
            driver.switchTo().window(detail.sourceHandle());
            driver.close();
            driver.switchTo().window(currentHandle);
        }
        private WorkerResult result(WorkerRecord record, WorkerStatus status, String message) {
            return new WorkerResult(record.row(), record.code(), record.name(), record.searchName(), status, message);
        }
        private void recoverList() {
            try {
                if (employeeSearchControls().isEmpty()) driver.get(site.employeeUrl());
                waitForEmployeeReady(timeout);
                String keep = driver.getWindowHandle();
                for (String handle : new HashSet<>(driver.getWindowHandles())) {
                    if (handle.equals(keep)) continue;
                    driver.switchTo().window(handle);
                    driver.close();
                }
                driver.switchTo().window(keep);
            } catch (Exception ignored) { }
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
