package com.demo.cdp.service;

import com.demo.cdp.config.AppConfig;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Chrome CDP 连接管理 —— 延迟初始化，读取 DevToolsActivePort，回退到 HTTP 端点。
 */
@Service
public class ChromeLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(ChromeLifecycleService.class);

    private final AppConfig config;
    private Playwright playwright;
    private Browser browser;

    public ChromeLifecycleService(AppConfig config) {
        this.config = config;
    }

    public synchronized Browser getBrowser() {
        if (browser == null) {
            browser = connect();
        }
        return browser;
    }

    private Browser connect() {
        Playwright pw = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));

        Browser b = null;

        // 1) 优先 DevToolsActivePort
        String wsUrl = findDevToolsWsUrl();
        if (wsUrl != null) {
            try {
                b = pw.chromium().connectOverCDP(wsUrl);
                log.info("✅ CDP 已连接 (DevToolsActivePort), {} 个标签页", b.contexts().size());
            } catch (Exception e) {
                log.warn("DevToolsActivePort 连接失败: {}", e.getMessage());
            }
        }

        // 2) 回退 HTTP 端点
        if (b == null) {
            String httpUrl = "http://127.0.0.1:" + config.chrome().port();
            try {
                b = pw.chromium().connectOverCDP(httpUrl);
                log.info("✅ CDP 已连接 (HTTP 端点), {} 个标签页", b.contexts().size());
            } catch (Exception e) {
                pw.close();
                throw new IllegalStateException(
                        "无法连接 Chrome CDP。请确保 Chrome 以 --remote-debugging-port="
                                + config.chrome().port() + " 启动", e);
            }
        }

        // 全部成功，原子赋值
        this.playwright = pw;
        this.browser = b;
        return b;
    }

    private String findDevToolsWsUrl() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null) return null;

        Path dtFile = Path.of(localAppData, "Google\\Chrome\\User Data\\DevToolsActivePort");
        if (!Files.exists(dtFile)) return null;

        try {
            List<String> lines = Files.readAllLines(dtFile);
            if (lines.size() < 2) return null;
            String port = lines.get(0).strip();
            if (!port.matches("\\d+")) return null;
            return "ws://127.0.0.1:" + port + lines.get(1).strip();
        } catch (Exception e) {
            return null;
        }
    }

    @PreDestroy
    public void cleanup() {
        if (browser != null) {
            try {
                browser.close();
            } catch (Exception ignored) {}
        }
        if (playwright != null) {
            try { playwright.close();
            } catch (Exception ignored) {}
        }
    }
}
