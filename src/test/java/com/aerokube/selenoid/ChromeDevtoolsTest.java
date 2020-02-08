package com.aerokube.selenoid;

import com.github.kklisura.cdt.protocol.commands.Runtime;
import com.github.kklisura.cdt.protocol.commands.*;
import com.github.kklisura.cdt.protocol.types.css.RuleUsage;
import com.github.kklisura.cdt.protocol.types.css.SourceRange;
import com.github.kklisura.cdt.protocol.types.dom.RGBA;
import com.github.kklisura.cdt.protocol.types.fetch.RequestPattern;
import com.github.kklisura.cdt.protocol.types.network.Request;
import com.github.kklisura.cdt.protocol.types.network.Response;
import com.github.kklisura.cdt.protocol.types.overlay.HighlightConfig;
import com.github.kklisura.cdt.services.ChromeDevToolsService;
import com.github.kklisura.cdt.services.WebSocketService;
import com.github.kklisura.cdt.services.config.ChromeDevToolsServiceConfiguration;
import com.github.kklisura.cdt.services.impl.ChromeDevToolsServiceImpl;
import com.github.kklisura.cdt.services.impl.WebSocketServiceImpl;
import com.github.kklisura.cdt.services.invocation.CommandInvocationHandler;
import com.github.kklisura.cdt.services.utils.ProxyUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import static com.github.kklisura.cdt.protocol.types.network.ErrorReason.FAILED;

class ChromeDevtoolsTest {

    private static final String URL = "https://aerokube.com/chrome-developer-tools-protocol-java-example/";

    private RemoteWebDriver driver;
    private ChromeDevToolsService devtools;

    @BeforeEach
    void setUp() throws Exception {
        // Init standard Selenium session
        DesiredCapabilities caps = new DesiredCapabilities();
        caps.setBrowserName("chrome");
        driver = new RemoteWebDriver(new URL("http://localhost:4444/wd/hub"), caps);

        // Init ChromeDevtools client
        WebSocketService webSocketService = WebSocketServiceImpl.create(new URI(String.format("ws://localhost:4444/devtools/%s/page", driver.getSessionId())));
        CommandInvocationHandler commandInvocationHandler = new CommandInvocationHandler();
        Map<Method, Object> commandsCache = new ConcurrentHashMap<>();
        devtools =
                ProxyUtils.createProxyFromAbstract(
                        ChromeDevToolsServiceImpl.class,
                        new Class[] {WebSocketService.class, ChromeDevToolsServiceConfiguration.class},
                        new Object[] {webSocketService, new ChromeDevToolsServiceConfiguration()},
                        (unused, method, args) ->
                                commandsCache.computeIfAbsent(
                                        method,
                                        key -> {
                                            Class<?> returnType = method.getReturnType();
                                            return ProxyUtils.createProxy(returnType, commandInvocationHandler);
                                        }));
        commandInvocationHandler.setChromeDevToolsService(devtools);
    }

    @AfterEach
    void tearDown() {
        if (devtools != null && !devtools.isClosed()) {
            devtools.close();
            devtools = null;
        }
        if (driver != null) {
            driver.quit();
            driver = null;
        }
    }

    @Test
    void testTakeScreenshot() throws Exception {
        Page page = devtools.getPage();
        navigate(page);
        takeScreenshot(page, "testScreenshot.png");
    }

    private void navigate(Page page) throws InterruptedException {
        page.enable();
        CountDownLatch countDownLatch = new CountDownLatch(1);
        page.onLoadEventFired(e -> countDownLatch.countDown());
        page.navigate(URL);
        countDownLatch.await();
    }

    private void takeScreenshot(Page page, String fileName) throws Exception {
        String encodedScreenshot = page.captureScreenshot();
        try (FileOutputStream fos = new FileOutputStream(fileName)) {
            fos.write(Base64.getDecoder().decode(encodedScreenshot));
            fos.flush();
        }
    }

    @Test
    void testPrintNetworkRequests() throws Exception {
        Network network = devtools.getNetwork();
        Map<String, Request> requests = new ConcurrentHashMap<>();
        network.onRequestWillBeSent(
                e -> requests.put(e.getRequestId(), e.getRequest())
        );
        network.onResponseReceived(e -> {
            Request request = requests.get(e.getRequestId());
            if (request != null) {
                Response response = e.getResponse();
                Double duration = response.getTiming() != null ? response.getTiming().getReceiveHeadersEnd() : 0d;
                System.out.printf("%s %s %f ms\n", request.getMethod(), request.getUrl(), duration);
            }
        });
        network.onLoadingFailed(e -> {
            // E.g. mixed-content and CORS requests blocked by browser
            Request request = requests.get(e.getRequestId());
            if (request != null) {
                System.out.printf("%s %s %s\n", request.getMethod(), request.getUrl(), e.getBlockedReason().name());
            }
        });
        network.enable();

        Page page = devtools.getPage();
        navigate(page);
    }

    @Test
    void testAbortNetworkRequests() throws Exception {
        Fetch fetch = devtools.getFetch();
        fetch.onRequestPaused(
                e -> fetch.failRequest(e.getRequestId(), FAILED)
        );
        RequestPattern requestPattern = new RequestPattern();
        requestPattern.setUrlPattern("*chrome.png");
        fetch.enable(Collections.singletonList(requestPattern), true);

        Page page = devtools.getPage();
        navigate(page);

        takeScreenshot(page, "testAbortNetworkRequest.png");
    }

    @Test
    void testMockNetworkRequests() throws Exception {
        byte[] fileContents = Files.readAllBytes(Paths.get("firefox.png"));
        String encodedFileContents = new String(Base64.getEncoder().encode(fileContents));
        Fetch fetch = devtools.getFetch();
        fetch.onRequestPaused(
                e -> fetch.fulfillRequest(
                        e.getRequestId(),
                        200,
                        Collections.emptyList(),
                        encodedFileContents,
                        "OK"
                )
        );
        RequestPattern requestPattern = new RequestPattern();
        requestPattern.setUrlPattern("*chrome.png");
        fetch.enable(Collections.singletonList(requestPattern), true);

        Page page = devtools.getPage();
        navigate(page);

        takeScreenshot(page, "testMockNetworkRequest.png");
    }

    @Test
    void testPrintConsoleMessages() throws Exception {
        Runtime runtime = devtools.getRuntime();
        runtime.onConsoleAPICalled(
                e -> System.out.printf(
                    "%s %s\n",
                    e.getType(),
                    e.getArgs().stream()
                            .map(a -> String.valueOf(a.getValue()))
                            .collect(Collectors.joining("; "))
                )
        );
        runtime.enable();

        Page page = devtools.getPage();
        navigate(page);
    }

    @Test
    void testPrintConsoleExceptions() throws Exception {
        Runtime runtime = devtools.getRuntime();
        runtime.onExceptionThrown(
                e -> System.out.println(
                        e.getExceptionDetails().getException().getValue()
                )
        );
        runtime.enable();

        Page page = devtools.getPage();
        navigate(page);
    }

    @Test
    void testCSSCoverage() throws Exception {
        DOM dom = devtools.getDOM();
        dom.enable();

        CSS css = devtools.getCSS();
        css.enable();

        Page page = devtools.getPage();
        navigate(page);

        css.startRuleUsageTracking();

        List<RuleUsage> ruleUsages = css.stopRuleUsageTracking();
        ruleUsages.forEach(r -> {
            String styleSheetText = css.getStyleSheetText(r.getStyleSheetId());
            String usedRule = styleSheetText.substring(
                    r.getStartOffset().intValue(),
                    r.getEndOffset().intValue()
            );
            System.out.println(usedRule);
        });
    }

    @Test
    void testAddCSSRule() throws Exception {
        DOM dom = devtools.getDOM();
        dom.enable();

        SourceRange sourceRange = new SourceRange();
        sourceRange.setStartLine(0);
        sourceRange.setStartColumn(0);
        sourceRange.setEndLine(0);
        sourceRange.setEndColumn(0);

        CSS css = devtools.getCSS();
        css.onStyleSheetAdded(
                e -> css.addRule(
                        e.getHeader().getStyleSheetId(),
                        "h1 {color: red;}",
                        sourceRange
                )
        );
        css.enable();

        Page page = devtools.getPage();
        navigate(page);

        takeScreenshot(page, "testAddCSSRule.png");
    }

    @Test
    void testHighlightNode() throws Exception {
        Page page = devtools.getPage();
        navigate(page);

        DOM dom = devtools.getDOM();
        Integer h1 = dom.querySelector(dom.getDocument().getNodeId(), "h1");

        Overlay overlay = devtools.getOverlay();
        overlay.highlightNode(highlightConfig(), h1, null, null, null);

        takeScreenshot(page, "testHighlightNode.png");
    }

    private static HighlightConfig highlightConfig() {
        HighlightConfig highlightConfig = new HighlightConfig();
        highlightConfig.setBorderColor(rgba(255, 229, 153, 0.66));
        highlightConfig.setContentColor(rgba(111, 168, 220, 0.66));
        highlightConfig.setCssGridColor(rgba(75, 0, 130, 0));
        highlightConfig.setEventTargetColor(rgba(255, 196, 196, 0.66));
        highlightConfig.setMarginColor(rgba(246, 178, 107, 0.66));
        highlightConfig.setPaddingColor(rgba(147, 196, 125, 0.55));
        highlightConfig.setShapeColor(rgba(96, 82, 117, 0.8));
        highlightConfig.setShapeMarginColor(rgba(96, 82, 127, 0.6));
        highlightConfig.setShowExtensionLines(true);
        highlightConfig.setShowInfo(true);
        highlightConfig.setShowRulers(true);
        highlightConfig.setShowStyles(false);
        return highlightConfig;
    }

    private static RGBA rgba(int r, int g, int b, double a) {
        RGBA result = new RGBA();
        result.setR(r);
        result.setG(g);
        result.setB(b);
        result.setA(a);
        return result;
    }

}
