package com.demo.cdp.service;

import com.demo.cdp.config.AppConfig;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Map;

/**
 * Chrome CDP 连接 —— 和原版 run.bat 逻辑一致：直接连，不做 HTTP 预检。
 *
 * 优先读取 DevToolsActivePort（chrome://inspect 开启的 CDP），
 * 回退到配置端口。连接失败则报错，绝不碰用户已有的 Chrome。
 */
@Service
public class ChromeLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(ChromeLifecycleService.class);

    private final AppConfig config;
    //是整个 Playwright 库的入口点。它的角色类似于"浏览器引擎管理器"——必须先 Playwright.create() 创建一个实例，才能通过它去连接或启动浏览器
    private Playwright playwright;
    //Chrome 浏览器的引用——所有搜索操作从这里开始
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

    // ─── Playwright 连接（和原版 CdpTestDemo 一样的逻辑）───────

    private void connect() {
        String wsUrl = readDevToolsWsUrl();
        String cdpUrl = wsUrl != null ? wsUrl : "http://127.0.0.1:" + config.chrome().port();

        log.info("🔗 连接 Chrome CDP: {} ...", cdpUrl);

        // ═══════════════════════════════════════════════════════════
        // 关键设计：用局部变量承接，全部成功后才赋给字段。
        // 这样失败时字段不受任何污染——browser 保持 null，
        // 下次 getBrowser() 会重新 connect()。
        // ═══════════════════════════════════════════════════════════
        Playwright newPlaywright = null;
        Browser newBrowser = null;

        try {
            // Playwright.create() 检查本地驱动、初始化引擎
            newPlaywright = Playwright.create(new Playwright.CreateOptions()
                    .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));

            // 核心：CDP 连接用户已有的 Chrome
            newBrowser = newPlaywright.chromium().connectOverCDP(cdpUrl);

            // 确认连接有效
            int contextCount = newBrowser.contexts().size();
            log.info("   ✅ 已连接，当前标签页数: {}", contextCount);

            // ─── 全部成功！原子性地赋给字段 ───
            playwright = newPlaywright;
            browser = newBrowser;

        } catch (Exception e) {
            // ─── 失败清理：只清理本次创建的局部对象 ───
            // 字段完全没有被污染（保持 null），下次 getBrowser() 会重试

            // 关闭 Browser（如果已创建）
            if (newBrowser != null) {
                try { newBrowser.close(); } catch (Exception ignored) {}
            }

            // 关闭 Playwright 引擎（如果已创建）
            // newPlaywright 为 null 表示 Playwright.create() 本身就失败了——不需要 close
            if (newPlaywright != null) {
                try { newPlaywright.close(); } catch (Exception ignored) {}
            }

            throw new IllegalStateException("""

                    ❌ Chrome CDP 连接失败！

                       请先开启 Chrome 远程调试，二选一：
                       1. chrome://inspect/#remote-debugging → 勾选 "Allow remote debugging"
                       2. 关闭 Chrome，运行 setup_chrome_debug.bat 后重新打开 Chrome

                       确认 CDP 端口 %d 可访问后，重新启动应用。\
                   """.formatted(config.chrome().port()), e);
        }
    }

    /**
     * playwright是通过ws协议操作chrome CDP的
     * 从 DevToolsActivePort 文件读取完整 WebSocket URL。
     * chrome://inspect 开启远程调试后 Chrome 会写入这个文件。
     * - ws:// 是 WebSocket 协议的 URL scheme——Playwright 内部通过 WebSocket 与 Chrome 通信
     * - http:// 也可以传给 connectOverCDP()——Playwright 会先发一个 HTTP GET 到 /json/version 获取 WebSocket URL，然后自动升级到 WebSocket
     * - 所以两种 URL 都有效：直接给 WebSocket URL 跳过了 HTTP 预检步骤（快一点点），给 HTTP URL 则多一步内部解析
     *
     * 这个文件的来龙去脉
     * 当用户在 Chrome 地址栏输入 chrome://inspect/#remote-debugging 并勾选 "Allow remote debugging" 后，Chrome 会在用户数据目录下写入一个文件：
     * %LOCALAPPDATA%\Google\Chrome\User Data\DevToolsActivePort
     * 这个文件的内容只有两行：
     * 实际的调试端口号 (9222)
     * /devtools/browser/c1e2a3b4-c5d6-4e7f-8a9b-0c1d2e3f4a5b   ← 第二行：WebSocket 路径
     */
    private String readDevToolsWsUrl() {
        try {
            var dtFile = Path.of(
                    System.getenv("LOCALAPPDATA"),
                    "Google", "Chrome", "User Data", "DevToolsActivePort");
            var lines = java.nio.file.Files.readAllLines(dtFile);
            return "ws://127.0.0.1:" + lines.get(0).strip() + lines.get(1).strip();
        } catch (Exception e) {
            return null;
        }
    }

    // ─── 清理 ─────────────────────────────────────────────────
    //PreDestroy 注解的方法会在 Spring 容器关闭时被自动调用——保证资源释放即使开发者忘记调用 cleanup() 也不会泄漏
    @PreDestroy
    public void cleanup() {
        if (browser != null) {
            try { browser.close(); } catch (Exception ignored) {}
            browser = null;
        }
        if (playwright != null) {
            try { playwright.close(); } catch (Exception ignored) {}
            playwright = null;
        }
        log.info("🧹 Playwright 资源已释放");
    }
}
