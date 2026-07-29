package com.demo.cdp.service;

import com.demo.cdp.config.AppConfig;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Chrome CDP 连接 —— 多策略连接，优先读取 DevToolsActivePort 文件，回退到 HTTP 端点。
 *
 * <h3>连接策略</h3>
 * <ol>
 *   <li>读取 DevToolsActivePort 文件获取完整 WebSocket URL（绕过 HTTP，最可靠）</li>
 *   <li>HTTP 端点 {@code http://127.0.0.1:{port}}（Playwright 内部通过 /json/version 发现 WS URL）</li>
 * </ol>
 *
 * <p>DevToolsActivePort 文件会从多个可能的 Chrome 用户数据目录中搜索，
 * 覆盖默认版、Beta、Dev、Canary 等渠道。
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

    /**
     * 获取已连接的 Browser 实例（延迟初始化，线程安全）。
     */
    public synchronized Browser getBrowser() {
        if (browser == null) {
            connect();
        }
        return browser;
    }

    // ─── 连接入口 ─────────────────────────────────────────────

    /**
     * 多策略连接 Chrome CDP。
     * 使用局部变量承接资源，全部成功后才赋给字段 —— 失败时字段不受污染。
     */
    private void connect() {
        List<String> urlsToTry = new ArrayList<>();

        // ── 策略 1: DevToolsActivePort → 完整 WebSocket URL ──
        String wsUrl = readDevToolsWsUrl();
        if (wsUrl != null) {
            urlsToTry.add(wsUrl);
        } else {
            log.info("📁 DevToolsActivePort 文件未找到（已搜索所有常见 Chrome 用户数据目录）");
            log.info("   → 将尝试 HTTP 端点连接");
        }

        // ── 策略 2: HTTP 端点 ──
        String httpUrl = "http://127.0.0.1:" + config.chrome().port();
        urlsToTry.add(httpUrl);

        // ── 逐个尝试 ──
        Exception lastError = null;
        for (String cdpUrl : urlsToTry) {
            try {
                log.info("🔗 尝试连接 Chrome CDP: {} ...", cdpUrl);
                ConnectionResult result = doConnect(cdpUrl);
                // 全部成功，原子赋值
                playwright = result.playwright();
                browser = result.browser();
                log.info("   ✅ CDP 连接成功，当前标签页数: {}", browser.contexts().size());
                return;
            } catch (Exception e) {
                log.warn("   ❌ 连接失败: {}", e.getMessage());
                lastError = e;
            }
        }

        // ── 所有策略都失败 ──
        throw new IllegalStateException("""

                ❌ Chrome CDP 连接失败（已尝试所有策略）！

                   确认 Chrome 远程调试已开启，二选一：
                   1. 运行 setup_chrome_debug.bat 创建带调试端口的桌面快捷方式
                   2. 手动启动 Chrome:
                      "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe" --remote-debugging-port=%d

                   ⚠️  注意：chrome://inspect 方式可能不开启 HTTP 端点，
                   请使用命令行 --remote-debugging-port 方式启动 Chrome。

                   当前配置端口: %d
                   最后错误: %s\
               """.formatted(config.chrome().port(), config.chrome().port(),
                lastError != null ? lastError.getMessage() : "未知"),
                lastError);
    }

    // ─── 单次连接 ────────────────────────────────────────────

    /**
     * 执行一次 CDP 连接，返回 Playwright + Browser。
     * 失败时自动清理已创建的资源。
     */
    private ConnectionResult doConnect(String cdpUrl) {
        Playwright newPlaywright = null;
        try {
            newPlaywright = Playwright.create(new Playwright.CreateOptions()
                    .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));

            Browser newBrowser = newPlaywright.chromium().connectOverCDP(cdpUrl);

            // 确认连接有效
            int contextCount = newBrowser.contexts().size();
            log.info("   ✅ 已连接 ({} 个标签页上下文)", contextCount);

            return new ConnectionResult(newPlaywright, newBrowser);
        } catch (Exception e) {
            // 失败：清理本次创建的 Playwright
            if (newPlaywright != null) {
                try {
                    newPlaywright.close();
                } catch (Exception ignored) {
                }
            }
            throw e;
        }
    }

    private record ConnectionResult(Playwright playwright, Browser browser) {
    }

    // ─── DevToolsActivePort 发现 ─────────────────────────────

    /**
     * 从 Chrome 用户数据目录读取 DevToolsActivePort 文件，
     * 构建完整 WebSocket URL。
     *
     * <p>搜索路径覆盖默认、Beta、Dev、Canary 等 Chrome 渠道。
     *
     * @return 完整的 WebSocket URL（如 {@code ws://127.0.0.1:9222/devtools/browser/...}），
     *         未找到则返回 {@code null}
     */
    private String readDevToolsWsUrl() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isEmpty()) {
            log.debug("   LOCALAPPDATA 环境变量不可用");
            return null;
        }

        // 搜索多个可能的 Chrome 用户数据目录
        String[][] searchPaths = {
                // { 基础路径 (相对 %LOCALAPPDATA%), 标签 }
                {"Google\\Chrome\\User Data", "Chrome 稳定版"},
                {"Google\\Chrome Beta\\User Data", "Chrome Beta"},
                {"Google\\Chrome Dev\\User Data", "Chrome Dev"},
                {"Google\\Chrome SxS\\User Data", "Chrome Canary"},
        };

        for (String[] entry : searchPaths) {
            String relativePath = entry[0];
            String label = entry[1];
            Path dtFile = Path.of(localAppData, relativePath, "DevToolsActivePort");

            if (Files.exists(dtFile)) {
                try {
                    List<String> lines = Files.readAllLines(dtFile);
                    if (lines.size() >= 2) {
                        String portStr = lines.get(0).strip();
                        String wsPath = lines.get(1).strip();

                        // 验证端口是否为数字（排除 pipe 路径等非 TCP 调试模式）
                        if (!portStr.matches("\\d+")) {
                            log.warn("   ⚠️ DevToolsActivePort 第一行不是端口号: '{}'", portStr);
                            log.warn("   → Chrome 可能使用了 pipe 调试模式（chrome://inspect），");
                            log.warn("   → 请改用 --remote-debugging-port={} 命令行参数启动 Chrome",
                                    config.chrome().port());
                            continue; // 尝试下一个路径
                        }

                        String url = "ws://127.0.0.1:" + portStr + wsPath;
                        log.info("   📁 找到 DevToolsActivePort ({}): {}", label, dtFile);
                        log.info("   → WebSocket URL: {}", url);
                        return url;
                    }
                } catch (IOException e) {
                    log.debug("   读取 {} 失败: {}", dtFile, e.getMessage());
                }
            }
        }

        return null;
    }

    // ─── 清理 ─────────────────────────────────────────────────

    @PreDestroy
    public void cleanup() {
        if (browser != null) {
            try {
                browser.close();
            } catch (Exception ignored) {
            }
            browser = null;
        }
        if (playwright != null) {
            try {
                playwright.close();
            } catch (Exception ignored) {
            }
            playwright = null;
        }
        log.info("🧹 Playwright 资源已释放");
    }
}
