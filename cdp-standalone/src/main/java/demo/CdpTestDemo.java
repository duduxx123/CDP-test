package demo;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;

import java.util.List;

/**
 * Playwright for Java — CDP 连接 Chrome，Bing 搜索自动化。
 *
 * <p>默认模式：connectOverCDP 连接已有 Chrome（保留所有登录态）。
 * Chrome 的启动由外部脚本（run.bat）管理。
 */
public class CdpTestDemo {

    private static final String SEARCH_QUERY = "魔法少女小圆";
    private static final int RESULT_COUNT = 10;

    public static void main(String[] args) {
        int port = 9222;
        String wsUrl = null;
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[i + 1]);
            }
            if ("--ws".equals(args[i]) && i + 1 < args.length) {
                wsUrl = args[i + 1];
            }
        }

        // Try to read WebSocket URL from DevToolsActivePort first
        if (wsUrl == null) {
            try {
                var dtFile = java.nio.file.Path.of(
                    System.getenv("LOCALAPPDATA"),
                    "Google", "Chrome", "User Data", "DevToolsActivePort");
                var lines = java.nio.file.Files.readAllLines(dtFile);
                wsUrl = "ws://127.0.0.1:" + lines.get(0).strip() + lines.get(1).strip();
            } catch (Exception ignored) {}
        }

        String cdpUrl = wsUrl != null ? wsUrl : "http://127.0.0.1:" + port;

        System.out.println("=".repeat(60));
        System.out.println("  Playwright CDP Demo — Bing 搜索 " + SEARCH_QUERY);
        System.out.println("=".repeat(60));
        System.out.println();

        try (Playwright playwright = Playwright.create()) {

            System.out.println("🔗 连接 Chrome CDP: " + cdpUrl + " ...");
            Browser browser = playwright.chromium().connectOverCDP(cdpUrl);
            System.out.println("   ✅ 已连接，当前标签页数: " + browser.contexts().size());

            BrowserContext context = browser.contexts().getFirst();
            Page page = context.newPage();

            searchBing(page, SEARCH_QUERY);
            printResults(extractResults(page, RESULT_COUNT));

            System.out.println("✅ 完成。浏览器保持打开，按 Enter 退出...");
            System.in.read();

        } catch (Exception e) {
            System.err.println("❌ 连接失败！请先通过 run.bat 启动 Chrome。");
            System.err.println("   错误: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 搜索 & 提取
    // ═══════════════════════════════════════════════════════════

    private static void searchBing(Page page, String query) {
        String url = "https://www.bing.com/search?q="
                + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("🔍 搜索: " + query);
        page.navigate(url);
        System.out.println("   ✅ " + page.title());
        page.waitForSelector("#b_results",
                new Page.WaitForSelectorOptions().setTimeout(15000));
        System.out.println("   ✅ 搜索结果已加载\n");
    }

    private static List<SearchResult> extractResults(Page page, int count) {
        var items = page.locator("#b_results > li.b_algo");
        int total = items.count();
        System.out.println("   📋 共 " + total + " 条，取前 " + Math.min(count, total) + " 条");

        return items.all().stream()
                .limit(count)
                .map(el -> {
                    try {
                        String title = el.locator("h2 a").innerText();
                        String url = el.locator("h2 a").getAttribute("href");
                        String snippet = "";
                        try {
                            var p = el.locator(".b_caption p");
                            if (p.count() > 0) snippet = p.first().innerText();
                        } catch (Exception ignored) {}
                        return new SearchResult(title, url, snippet);
                    } catch (Exception e) {
                        return new SearchResult("(解析失败)", "", "");
                    }
                })
                .toList();
    }

    private static void printResults(List<SearchResult> results) {
        System.out.println("=".repeat(60));
        System.out.println("  Bing 搜索结果 — \"" + SEARCH_QUERY + "\"");
        System.out.println("=".repeat(60) + "\n");
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            System.out.println("【" + (i + 1) + "】 " + r.title());
            System.out.println("     URL: " + r.url());
            if (!r.snippet().isEmpty()) {
                String snip = r.snippet().length() > 120
                        ? r.snippet().substring(0, 120) + "..."
                        : r.snippet();
                System.out.println("     摘要: " + snip);
            }
            System.out.println();
        }
    }

    private record SearchResult(String title, String url, String snippet) {}
}
