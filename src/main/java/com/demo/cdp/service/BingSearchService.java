package com.demo.cdp.service;

import com.demo.cdp.model.SearchResult;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Bing 搜索核心逻辑 —— 从原 CdpTestDemo 提取，使用 CDP 连接的 Chrome。
 */
@Service
public class BingSearchService {

    private static final Logger log = LoggerFactory.getLogger(BingSearchService.class);

    private final ChromeLifecycleService chromeService;

    public BingSearchService(ChromeLifecycleService chromeService) {
        this.chromeService = chromeService;
    }

    /**
     * 执行 Bing 搜索并返回结果列表。
     */
    public List<SearchResult> search(String query, int count) {
        Browser browser = chromeService.getBrowser();
        /*
        *  browser.contexts()：返回 List<BrowserContext>。在 Playwright 中，BrowserContext 对应一个浏览器用户 Profile
        * （隔离的 Cookie、LocalStorage、会话）
        * 通过 CDP 连接已有 Chrome 时，每个已打开的 Profile 窗口就是一个 BrowserContext。
        - .getFirst()：取列表的第一个。这里假设用户至少打开了一个 Chrome 窗口。
        * 用第一个 context 意味着使用默认 Profile——所有已登录的网站（邮箱、社交媒体等）的 Cookie 和登录态都在里面。
        * */
        var context = browser.contexts().getFirst();
        Page page = context.newPage();  //创建新标签页

        try {
            navigateToBing(page, query);  //导航到bing搜索结果页面
            return extractResults(page, count);  //从页面中提取搜索结果并返回
        } finally {
            // 搜索完成后关闭标签页，但不关闭浏览器
            if (page != null) {
                page.close();
            }
        }
    }

    private void navigateToBing(Page page, String query) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);  //将搜索关键词转化为URL安全的编码防止中文乱码
        String url = "https://www.bing.com/search?q=" + encoded;
        log.info("🔍 搜索: {}", query);
        //navigate() 方法在页面触发 load 事件后返回（默认行为）(load事件在html和css加载完成后返回，但js异步操作不一定加载完成)
        page.navigate(url);  //Playwright 的核心导航操作。page.navigate(url) 等同于用户在浏览器地址栏输入 URL 并回车
        log.info("   ✅ {}", page.title());
        //这是保证数据可用的关键步骤。虽然 navigate() 返回了（表示 HTML 已加载）
        // 但搜索结果可能是异步渲染的（JavaScript 动态填充），所以需要等待特定元素出现
        page.waitForSelector("#b_results",  //css选择器，阻塞当前线程，直到匹配选择器的元素出现在 DOM 中并变为可见
                new Page.WaitForSelectorOptions().setTimeout(15000));  //等待15秒#b_results
        log.info("   ✅ 搜索结果已加载");
    }

    private List<SearchResult> extractResults(Page page, int count) {
        //创建一个 Locator——Playwright 的核心元素定位 API。它不是一个具体的元素，而是一个动态查询（每次使用时会重新查找）
        //  - CSS 选择器 #b_results > li.b_algo 含义：
        //      - #b_results：ID 为 b_results 的元素（Bing 搜索结果列表容器 <ol>）
        //    - >：直接子元素
        //    - li.b_algo：CSS class 为 b_algo 的 <li> 元素（每个搜索结果卡片）
        //  - 所以这定位到每一个搜索结果项
        var items = page.locator("#b_results > li.b_algo");
        int total = items.count();  //同步方法，立即返回当前 DOM 中匹配的元素数
        log.info("   📋 共 {} 条，取前 {} 条", total, Math.min(count, total));

        //items.all()：获取所有匹配的 ElementHandle（Playwright 中指向具体 DOM 元素的句柄）的列表。与 locator() 不同，all() 返回的是当前时刻的快照
        //- .stream()：转为 Java Stream，开启函数式管道
        //- .limit(count)：截取前 count 个元素。如果用户请求 5 条，即使有 10 条结果也只处理前 5 条
        return items.all().stream()
                .limit(count)
                .map(el -> {  //Stream 的 map 操作：将每个 ElementHandle（一个搜索结果卡片 <li>）转换为 SearchResult 对象。Lambda 的参数 el 就是单个 ElementHandle
                    try {
                        //在每个 <li class="b_algo"> 内部查找 <h2> 下的 <a> 标签
                        // .innerText() 返回该元素的可见文本（和用户在屏幕上看到的一致），会自动去除 HTML 标签
                        String title = el.locator("h2 a").innerText();
                        //获取href属性(超链接)
                        String url = el.locator("h2 a").getAttribute("href");
                        String snippet = "";  //摘要默认为空字符串——因为不是所有搜索结果都有摘要文字
                        try {
                            var p = el.locator(".b_caption p");  //搜索结果的文字摘要（snippet）
                            if (p.count() > 0) snippet = p.first().innerText();
                        } catch (Exception ignored) {}
                        return new SearchResult(title, url, snippet);
                    } catch (Exception e) {
                        return new SearchResult("(解析失败)", "", "");
                    }
                })
                .toList();  //转成List
    }
}
