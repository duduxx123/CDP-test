package com.demo.cdp.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 抖音网页版自动操作 —— 搜索 + 点指定视频 + 评论。
 * <p>
 * 复用 {@link ChromeLifecycleService} 的 CDP 连接，与 Bing/YouTube 共用同一个 Chrome 浏览器实例。
 * <p>
 * 抖音网页版使用 React SPA + CSS Modules（动态哈希类名），因此全部 DOM 操作采用
 * <b>多策略降级选择器 + JS evaluate 注入</b> 方式实现，并配套调试方法辅助排查。
 */
@Service
public class DouyinService {

    private static final Logger log = LoggerFactory.getLogger(DouyinService.class);

    private final ChromeLifecycleService chromeService;

    public DouyinService(ChromeLifecycleService chromeService) {
        this.chromeService = chromeService;
    }

    // ═══════════════════════════════════════════════════════════
    // 记录类型
    // ═══════════════════════════════════════════════════════════

    /**
     * 评论操作结果。
     */
    public record DouyinResult(boolean success, String videoTitle, String videoUrl, String comment) {
    }

    /**
     * 单条评论数据。
     */
    public record CommentItem(String author, String text, String time) {
    }

    /**
     * 评论获取结果。
     */
    public record CommentsResult(boolean success, String videoTitle, String videoUrl,
                                 List<CommentItem> comments) {
    }

    // ═══════════════════════════════════════════════════════════
    // 公共方法
    // ═══════════════════════════════════════════════════════════

    /**
     * 搜索抖音关键词，点进第 index 个视频并发表评论。
     *
     * @param keyword 搜索关键词
     * @param comment 要发表的评论内容
     * @param index   选择第几个视频（1-based，默认 1）
     * @return 操作结果
     */
    public DouyinResult searchAndComment(String keyword, String comment, int index) {
        int videoIndex = Math.max(1, index);
        Browser browser = chromeService.getBrowser();
        var context = browser.contexts().getFirst();
        Page page = context.newPage();

        try {
            // ─── 1. 搜索 ───
            searchDouyin(page, keyword);

            // ─── 2. 点击第 index 个视频 ───
            String videoUrl = clickVideoByIndex(page, videoIndex);

            // ─── 3. 等待视频页加载 ───
            waitForVideoPage(page);
            String title = page.title();
            log.info("🎬 视频标题: {}", title);

            // ─── 4. 检查登录状态 ───
            if (!isLoggedIn(page)) {
                log.warn("⚠️ 未登录抖音，无法评论");
                return new DouyinResult(false, page.title(),
                        page.url(), "(未登录抖音，请先在 Chrome 中登录 douyin.com)");
            }

            // ─── 5. 滚动到评论区 ───
            scrollToComments(page);

            // ─── 6. 发表评论 ───
            postComment(page, comment);

            log.info("✅ 抖音评论完成: {} → {}", videoUrl, comment);
            return new DouyinResult(true, title, videoUrl, comment);

        } catch (Exception e) {
            log.error("❌ 抖音操作失败: {}", e.getMessage(), e);
            return new DouyinResult(false, "(操作失败)", "", comment);

        } finally {
            log.info("📌 标签页保持打开，可手动查看");
        }
    }

    /**
     * 搜索抖音关键词，点进第 index 个视频，滚动加载并提取评论。
     *
     * @param keyword 搜索关键词
     * @param count   目标评论条数
     * @param index   选择第几个视频（1-based，默认 1）
     * @return 评论列表结果
     */
    public CommentsResult searchAndFetchComments(String keyword, int count, int index) {
        int target = count > 0 ? count : 10;
        int videoIndex = Math.max(1, index);
        Browser browser = chromeService.getBrowser();
        var context = browser.contexts().getFirst();
        Page page = context.newPage();

        try {
            // ─── 1. 搜索 ───
            searchDouyin(page, keyword);

            // ─── 2. 点击第 index 个视频 ───
            String videoUrl = clickVideoByIndex(page, videoIndex);

            // ─── 3. 等待视频页加载 ───
            waitForVideoPage(page);
            String title = page.title();
            log.info("🎬 视频标题: {}", title);

            // ─── 4. 滚动到评论区 ───
            scrollToComments(page);

            // ─── 5. 等待首批评论渲染 ───
            boolean hasComments = waitForCommentItems(page);
            if (!hasComments) {
                log.warn("⚠️ 未检测到评论（视频可能关闭了评论）");
                return new CommentsResult(true, title, videoUrl, List.of());
            }

            // ─── 5.5 调试：检查 DOM 结构 ───
            debugCommentsDOM(page);

            // ─── 6. 循环滚动 + 提取 ───
            List<CommentItem> collected = new ArrayList<>();
            Set<String> seen = new HashSet<>();       // "author|||text" 去重
            int staleRounds = 0;
            int maxRounds = 20;

            for (int round = 0; round < maxRounds && collected.size() < target; round++) {
                List<CommentItem> batch = extractVisibleComments(page);
                int before = collected.size();

                for (CommentItem item : batch) {
                    String key = item.author() + "|||" + item.text();
                    if (seen.add(key)) {
                        collected.add(item);
                        if (collected.size() >= target) break;
                    }
                }

                int added = collected.size() - before;
                log.info("   第 {} 轮: 提取 {} 条, 新增 {} 条, 已收集 {}/{}",
                        round + 1, batch.size(), added, collected.size(), target);

                if (collected.size() >= target) break;

                if (added == 0) {
                    staleRounds++;
                    if (staleRounds >= 3) {
                        log.info("   连续 {} 轮无新评论，停止加载", staleRounds);
                        break;
                    }
                } else {
                    staleRounds = 0;
                }

                // 向下滚动触发懒加载下一批
                page.evaluate("() => window.scrollBy(0, 800)");
                page.waitForTimeout(1500);
            }

            log.info("✅ 评论提取完成: {} 条 (目标 {} 条)", collected.size(), target);

            // ─── 逐条输出评论到控制台 ───
            System.out.println("=".repeat(60));
            System.out.printf("📺 视频: %s%n", title);
            System.out.printf("🔗 链接: %s%n", videoUrl);
            System.out.printf("💬 评论总数: %d / 目标 %d%n", collected.size(), target);
            System.out.println("-".repeat(60));
            for (int i = 0; i < collected.size(); i++) {
                CommentItem c = collected.get(i);
                System.out.printf("[%d] 👤 %s%n", i + 1, c.author());
                System.out.printf("    🕒 %s%n", c.time());
                System.out.printf("    💬 %s%n", c.text());
                System.out.println();
            }
            System.out.println("=".repeat(60));

            return new CommentsResult(true, title, videoUrl, collected);

        } catch (Exception e) {
            log.error("❌ 获取评论失败: {}", e.getMessage(), e);
            return new CommentsResult(false, "(操作失败)", "", List.of());

        } finally {
            log.info("📌 标签页保持打开，可手动查看");
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 导航 & 搜索
    // ═══════════════════════════════════════════════════════════

    /**
     * 导航到抖音搜索结果页。
     */
    private void searchDouyin(Page page, String keyword) {
        String encoded = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        String url = "https://www.douyin.com/search/" + encoded + "?type=video";
        log.info("🔍 抖音搜索: {}", keyword);
        page.navigate(url);

        // 等待搜索结果加载 —— 多策略
        try {
            // 策略 1: data-e2e 属性
            page.waitForSelector("[data-e2e=\"search-result-item\"]",
                    new Page.WaitForSelectorOptions().setTimeout(8000));
            log.info("   ✅ 搜索结果已加载 (data-e2e) — {}", page.title());
            return;
        } catch (Exception ignored) {}

        try {
            // 策略 2: 视频链接（任何包含 /video/ 的 a 标签）
            page.waitForSelector("a[href*=\"/video/\"]",
                    new Page.WaitForSelectorOptions().setTimeout(8000));
            log.info("   ✅ 搜索结果已加载 (video link) — {}", page.title());
            return;
        } catch (Exception ignored) {}

        // 策略 3: 兜底等待
        log.info("   ⏳ 选择器未命中，使用兜底等待...");
        page.waitForTimeout(5000);
        log.info("   ✅ 搜索结果页面: {}", page.title());
    }

    /**
     * 点击搜索结果中第 index 个视频（1-based），进入播放页。
     *
     * @return 视频 URL
     */
    private String clickVideoByIndex(Page page, int index) {
        log.info("🎬 定位第 {} 个视频...", index);

        // ─── 调试：输出搜索结果 DOM ───
        debugSearchDOM(page);

        // 通过 JS 查找所有视频链接，取第 index 个
        String videoUrl;
        try {
            Object result = page.evaluate(
                    "(idx) => {" +
                    "  const links = [];" +
                    "  const seen = new Set();" +
                    // 策略 1: 找所有 /video/ 链接（在搜索结果区域内）
                    "  const allLinks = document.querySelectorAll('a[href*=\"/video/\"]');" +
                    "  for (const a of allLinks) {" +
                    "    const href = a.getAttribute('href');" +
                    "    if (href && !href.includes('/user/') && !seen.has(href)) {" +
                    "      // 排除用户主页链接，只保留视频链接" +
                    "      const match = href.match(/\\/video\\/(\\d+)/);" +
                    "      if (match) {" +
                    "        seen.add(href);" +
                    "        links.push({ href: href, text: (a.textContent || '').trim().substring(0, 80) });" +
                    "      }" +
                    "    }" +
                    "  }" +
                    // 策略 2: 如果没找到足够的，找 data-e2e 卡片内的链接
                    "  if (links.length < idx) {" +
                    "    const cards = document.querySelectorAll('[data-e2e=\"search-result-item\"], [data-e2e*=\"video\"], div[class*=\"search\"] a[href*=\"/video/\"]');" +
                    "    for (const el of cards) {" +
                    "      const a = el.tagName === 'A' ? el : el.querySelector('a[href*=\"/video/\"]');" +
                    "      if (a) {" +
                    "        const href = a.getAttribute('href');" +
                    "        if (href && !seen.has(href)) {" +
                    "          const match = href.match(/\\/video\\/(\\d+)/);" +
                    "          if (match) {" +
                    "            seen.add(href);" +
                    "            links.push({ href: href, text: (a.textContent || '').trim().substring(0, 80) });" +
                    "          }" +
                    "        }" +
                    "      }" +
                    "    }" +
                    "  }" +
                    "  if (links.length === 0) return JSON.stringify({ error: 'no-links' });" +
                    "  const targetIdx = Math.min(idx - 1, links.length - 1);" +
                    "  const target = links[targetIdx];" +
                    "  return JSON.stringify({ href: target.href, text: target.text, total: links.length });" +
                    "}",
                    index);

            String json = (String) result;
            if (json.contains("\"error\":\"no-links\"")) {
                throw new RuntimeException("搜索结果中未找到视频链接，请检查抖音页面结构");
            }

            // 简单解析 JSON（避免引入 Jackson 依赖）
            String href = extractJsonValue(json, "href");
            String text = extractJsonValue(json, "text");
            int total = Integer.parseInt(extractJsonValue(json, "total"));

            log.info("   找到 {} 个视频，选中第 {} 个: {} — {}", total, index, text, href);

            videoUrl = href;
            if (videoUrl != null && !videoUrl.startsWith("http")) {
                videoUrl = "https://www.douyin.com" + videoUrl;
            }

            // 通过 JS 点击第 index 个视频链接，并处理可能的新标签页
            String finalUrl = videoUrl;
            Object clickResult = page.evaluate(
                    "(idx) => {" +
                    "  const links = [];" +
                    "  const seen = new Set();" +
                    "  const allLinks = document.querySelectorAll('a[href*=\"/video/\"]');" +
                    "  for (const a of allLinks) {" +
                    "    const href = a.getAttribute('href');" +
                    "    if (href && !href.includes('/user/') && !seen.has(href)) {" +
                    "      if (href.match(/\\/video\\/(\\d+)/)) {" +
                    "        seen.add(href);" +
                    "        links.push(a);" +
                    "      }" +
                    "    }" +
                    "  }" +
                    "  const targetIdx = Math.min(idx - 1, links.length - 1);" +
                    "  const target = links[targetIdx];" +
                    "  if (target) {" +
                    "    target.click();" +
                    "    return 'clicked:' + target.getAttribute('href');" +
                    "  }" +
                    "  return 'not-found';" +
                    "}",
                    index);
            log.info("   点击结果: {}", clickResult);

            if (String.valueOf(clickResult).contains("not-found")) {
                throw new RuntimeException("无法点击第 " + index + " 个视频");
            }

            // 等待页面响应
            page.waitForTimeout(2000);

            return finalUrl;

        } catch (Exception e) {
            log.warn("   JS 点击失败: {}，尝试 Playwright 原生点击", e.getMessage());
            // 回退：使用 Playwright locator 点击
            var allVideoLinks = page.locator("a[href*=\"/video/\"]");
            int count = allVideoLinks.count();
            if (count == 0) {
                throw new RuntimeException("搜索结果中未找到视频链接");
            }
            int targetIdx = Math.min(index - 1, count - 1);
            var targetLink = allVideoLinks.nth(targetIdx);
            videoUrl = targetLink.getAttribute("href");
            if (videoUrl != null && !videoUrl.startsWith("http")) {
                videoUrl = "https://www.douyin.com" + videoUrl;
            }
            targetLink.click();
            log.info("   ✅ Playwright 点击第 {} 个视频: {}", targetIdx + 1, videoUrl);
            page.waitForTimeout(2000);
            return videoUrl;
        }
    }

    /**
     * 从简易 JSON 字符串中提取指定 key 的字符串值（不依赖 Jackson）。
     */
    private String extractJsonValue(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start == -1) {
            search = "\"" + key + "\":";
            start = json.indexOf(search);
            if (start == -1) return "";
            start += search.length();
            int end = json.indexOf(",", start);
            if (end == -1) end = json.indexOf("}", start);
            return json.substring(start, end).trim();
        }
        start += search.length();
        int end = json.indexOf("\"", start);
        if (end == -1) return "";
        return json.substring(start, end)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    /**
     * 等待视频播放页加载完成。
     */
    private void waitForVideoPage(Page page) {
        try {
            // 策略 1: 等待 URL 变为 /video/ 格式
            page.waitForURL("**/video/**",
                    new Page.WaitForURLOptions().setTimeout(15000));
            log.info("   ✅ 进入视频页: {}", page.url());
        } catch (Exception e) {
            log.warn("   ⚠️ URL 未按预期变化，当前: {}", page.url());
        }
        // 额外等待渲染
        page.waitForTimeout(3000);
    }

    // ═══════════════════════════════════════════════════════════
    // 登录检测
    // ═══════════════════════════════════════════════════════════

    /**
     * 检查是否已登录抖音。
     * 通过检测页面上是否存在登录按钮或用户头像来判断。
     */
    private boolean isLoggedIn(Page page) {
        try {
            Object result = page.evaluate("() => {" +
                    // 已登录：右上角有头像/用户菜单
                    "const avatar = document.querySelector('[data-e2e=\"user-avatar\"], " +
                    "  img[class*=\"avatar\"], div[class*=\"avatar\"], " +
                    "  [data-e2e=\"profile-icon\"], [data-e2e*=\"header-avatar\"]');" +
                    "if (avatar) return true;" +
                    // 未登录：页面有 "登录" 按钮
                    "const allSpans = document.querySelectorAll('span');" +
                    "for (const s of allSpans) {" +
                    "  if (s.textContent.trim() === '登录') return false;" +
                    "}" +
                    "const allButtons = document.querySelectorAll('button');" +
                    "for (const b of allButtons) {" +
                    "  if (b.textContent.includes('登录')) return false;" +
                    "}" +
                    // 检查登录弹窗/模态框
                    "const loginModal = document.querySelector('[class*=\"login\"], [class*=\"Login\"]');" +
                    "if (loginModal && loginModal.offsetParent !== null) return false;" +
                    // 不确定，保守返回 false
                    "return false;" +
                    "}");
            boolean loggedIn = Boolean.TRUE.equals(result);
            if (loggedIn) {
                log.info("✅ 已登录抖音");
            }
            return loggedIn;
        } catch (Exception e) {
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 评论区定位
    // ═══════════════════════════════════════════════════════════

    /**
     * 滚动页面触发评论区懒加载。
     * 抖音评论区通常在视频下方或右侧，需要滚动到可见区域才会渲染。
     */
    private void scrollToComments(Page page) {
        log.info("📜 滚动页面触发评论区懒加载...");

        for (int i = 0; i < 15; i++) {
            // 先检查评论区是否已经可见
            boolean visible = (boolean) page.evaluate(
                    "() => {" +
                    // 多策略检测评论区可见性
                    "  const selectors = [" +
                    "    '[data-e2e=\"comment-list\"]'," +
                    "    '[data-e2e=\"comment-container\"]'," +
                    "    'div[class*=\"comment-list\"]'," +
                    "    'div[class*=\"commentList\"]'," +
                    "    'div[class*=\"comment-container\"]'," +
                    "    '#douyin-right-container'" +
                    "  ];" +
                    "  for (const sel of selectors) {" +
                    "    const el = document.querySelector(sel);" +
                    "    if (!el || el.offsetParent === null) continue;" +
                    "    const rect = el.getBoundingClientRect();" +
                    "    if (rect.bottom > 0 && rect.top < window.innerHeight) {" +
                    "      return true;" +
                    "    }" +
                    "  }" +
                    "  return false;" +
                    "}");
            if (visible) {
                log.info("   ✅ 评论区已可见 (第 {} 次检查)", i + 1);
                // 把评论区滚动到视口中间
                page.evaluate("() => {" +
                        "  const selectors = [" +
                        "    '[data-e2e=\"comment-list\"]'," +
                        "    '[data-e2e=\"comment-container\"]'," +
                        "    'div[class*=\"comment-list\"]'," +
                        "    'div[class*=\"commentList\"]'," +
                        "    'div[class*=\"comment-container\"]'," +
                        "    '#douyin-right-container'" +
                        "  ];" +
                        "  for (const sel of selectors) {" +
                        "    const el = document.querySelector(sel);" +
                        "    if (el && el.offsetParent !== null) {" +
                        "      el.scrollIntoView({ behavior: 'instant', block: 'center' });" +
                        "      return;" +
                        "    }" +
                        "  }" +
                        "}");
                page.waitForTimeout(1000);
                return;
            }

            // 还没出现，往下滚 500px 触发懒加载
            log.info("   第 {} 次滚动 (评论区尚未可见)", i + 1);
            page.evaluate("() => window.scrollBy(0, 500)");
            page.waitForTimeout(800);
        }

        log.warn("   ⚠️ 滚动 15 次后评论区仍未可见");
    }

    /**
     * 等待评论条目渲染。
     *
     * @return true 如果检测到评论，false 如果没有
     */
    private boolean waitForCommentItems(Page page) {
        String[] commentSelectors = {
                "[data-e2e=\"comment-item\"]",
                "div[class*=\"comment-item\"]",
                "div[class*=\"commentItem\"]",
                // 任何评论区容器内的列表项
                "[data-e2e=\"comment-list\"] > div",
                "div[class*=\"comment-list\"] > div",
                "div[class*=\"commentList\"] > div",
        };
        for (String sel : commentSelectors) {
            try {
                page.waitForSelector(sel,
                        new Page.WaitForSelectorOptions().setTimeout(8000));
                log.info("   ✅ 评论条目已渲染 (选择器: {})", sel);
                return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════
    // 评论提取
    // ═══════════════════════════════════════════════════════════

    /**
     * 从当前已渲染的评论 DOM 中提取所有评论数据。
     * <p>
     * 抖音前端使用 React + CSS Modules，类名为动态哈希值。
     * 采用多策略 JS 注入：优先 data-e2e 属性，其次类名部分匹配，最后文本解析兜底。
     */
    @SuppressWarnings("unchecked")
    private List<CommentItem> extractVisibleComments(Page page) {
        try {
            List<List<String>> raw = (List<List<String>>) page.evaluate("() => {" +
                    "const results = [];" +
                    // ═══════════════════════════════════════════
                    // 策略 1: data-e2e 属性选择器
                    // ═══════════════════════════════════════════
                    "let items = document.querySelectorAll('[data-e2e=\"comment-item\"]');" +
                    "if (items.length === 0) {" +
                    // ═══════════════════════════════════════════
                    // 策略 2: 类名部分匹配（CSS Modules 哈希前缀）
                    // ═══════════════════════════════════════════
                    "  items = document.querySelectorAll('div[class*=\"comment-item\"], div[class*=\"commentItem\"]');" +
                    "}" +
                    "if (items.length === 0) {" +
                    // ═══════════════════════════════════════════
                    // 策略 3: 在评论区容器内递归查找疑似评论节点
                    // ═══════════════════════════════════════════
                    "  const containers = document.querySelectorAll(" +
                    "    '[data-e2e=\"comment-list\"], " +
                    "    'div[class*=\"comment-list\"], div[class*=\"commentList\"], " +
                    "    'div[class*=\"comment-container\"], " +
                    "    '#douyin-right-container'" +
                    "  );" +
                    "  for (const container of containers) {" +
                    "    // 找所有包含文本且看起来像评论的叶子 div" +
                    "    const divs = container.querySelectorAll('div');" +
                    "    const candidates = [];" +
                    "    for (const div of divs) {" +
                    "      if (div.children.length >= 2 && div.children.length <= 6) {" +
                    "        const text = div.textContent.trim();" +
                    "        // 评论通常至少 3 个字，且包含时间关键词或用户名字样的短行" +
                    "        if (text.length > 5 && text.length < 2000) {" +
                    "          const hasName = div.querySelector('span, a, [class*=\"name\"], [class*=\"nick\"]');" +
                    "          const hasTime = /\\d+\\s*(分钟|小时|天|秒|刚刚|前)/.test(text);" +
                    "          if (hasName || hasTime) candidates.push(div);" +
                    "        }" +
                    "      }" +
                    "    }" +
                    "    if (candidates.length > 0) {" +
                    "      items = candidates;" +
                    "      break;" +
                    "    }" +
                    "  }" +
                    "}" +
                    "" +
                    "for (const item of items) {" +
                    "  try {" +
                    "    let author = '';" +
                    "    let text = '';" +
                    "    let time = '';" +
                    "    " +
                    // ── 提取作者 ──
                    "    const authorEl = item.querySelector(" +
                    "      '[data-e2e=\"comment-username\"], " +
                    "      'span[class*=\"username\"], span[class*=\"nickname\"], span[class*=\"name\"], " +
                    "      'a[class*=\"name\"], a[class*=\"nick\"]'" +
                    "    );" +
                    "    if (authorEl) author = authorEl.textContent.trim();" +
                    "" +
                    // ── 提取评论正文 ──
                    "    const textEl = item.querySelector(" +
                    "      '[data-e2e=\"comment-content\"], " +
                    "      'span[class*=\"text\"], span[class*=\"content\"], div[class*=\"content\"], " +
                    "      'p[class*=\"text\"], span[class*=\"desc\"]'" +
                    "    );" +
                    "    if (textEl) {" +
                    "      text = textEl.textContent.trim();" +
                    "    } else {" +
                    // 文本兜底：整个 item 的文本去掉作者和时间
                    "      const allText = item.textContent.trim();" +
                    "      if (author && allText.startsWith(author)) {" +
                    "        text = allText.substring(author.length).trim();" +
                    "      } else {" +
                    "        text = allText;" +
                    "      }" +
                    "    }" +
                    "" +
                    // ── 提取时间 ──
                    "    const timeEl = item.querySelector(" +
                    "      'span[class*=\"time\"], span[class*=\"date\"], span[class*=\"Time\"]'" +
                    "    );" +
                    "    if (timeEl) {" +
                    "      time = timeEl.textContent.trim();" +
                    "    } else {" +
                    // 时间兜底：从文本中匹配 "x分钟前", "x小时前", "x天前" 等
                    "      const allText = item.textContent;" +
                    "      const match = allText.match(/\\d+\\s*(分钟前|小时前|天前|秒前|周前|月前|刚刚)/);" +
                    "      if (match) time = match[0];" +
                    "    }" +
                    "" +
                    // ── 清理文本（去掉时间后缀） ──
                    "    if (time && text.endsWith(time)) {" +
                    "      text = text.substring(0, text.length - time.length).trim();" +
                    "    }" +
                    // 去掉回复前缀
                    "    text = text.replace(/^回复\\s*@?\\S+:?\\s*/, '').trim();" +
                    "" +
                    "    if (text.length > 1) {" +
                    "      results.push([author, text, time]);" +
                    "    }" +
                    "  } catch(e) {}" +
                    "}" +
                    "return results;" +
                    "}");

            if (raw == null) return List.of();

            List<CommentItem> items = new ArrayList<>();
            for (List<String> row : raw) {
                if (row.size() >= 3) {
                    items.add(new CommentItem(
                            row.get(0) != null ? row.get(0) : "",
                            row.get(1) != null ? row.get(1) : "",
                            row.get(2) != null ? row.get(2) : ""));
                }
            }
            return items;

        } catch (Exception e) {
            log.warn("   ⚠️ 评论提取失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 发表评论
    // ═══════════════════════════════════════════════════════════

    /**
     * 在视频下方发表评论。
     * <p>
     * 抖音评论区输入框通常是一个 contenteditable div 或 textarea，
     * 交互流程：
     * 1. 点击评论占位符展开评论框
     * 2. 在可编辑区域填入文字（采用模拟人类打字速度）
     * 3. 点击提交按钮
     */
    private void postComment(Page page, String comment) {
        log.info("💬 撰写评论: {}", comment);

        // ─── 0. 调试：打印评论区 DOM 结构 ───
        debugCommentsDOM(page);

        // ─── 1. 展开评论输入框 ───
        expandCommentBox(page);

        // ─── 2. 填入文字 ───
        fillCommentBox(page, comment);

        // ─── 3. 提交评论 ───
        clickSubmitButton(page);

        // 等评论提交完成
        page.waitForTimeout(3000);
    }

    /**
     * 展开评论输入框。
     * 通过 JS 查找评论输入入口并点击，支持多策略降级。
     */
    private void expandCommentBox(Page page) {
        log.info("   📝 展开评论输入框...");

        try {
            Object result = page.evaluate("() => {" +
                    // ═══════════════════════════════════════════
                    // 辅助函数：递归穿透 Shadow DOM（抖音可能用 Web Components）
                    // ═══════════════════════════════════════════
                    "function deepQuerySelector(root, predicate, maxDepth) {" +
                    "  if (maxDepth === undefined) maxDepth = 5;" +
                    "  if (maxDepth <= 0 || !root) return null;" +
                    "  try {" +
                    "    const all = root.querySelectorAll ? root.querySelectorAll('*') : [];" +
                    "    for (const el of all) {" +
                    "      try { if (predicate(el) && el.offsetParent !== null) return el; } catch(e) {}" +
                    "      try {" +
                    "        if (el.shadowRoot) {" +
                    "          const found = deepQuerySelector(el.shadowRoot, predicate, maxDepth - 1);" +
                    "          if (found) return found;" +
                    "        }" +
                    "      } catch(e) {}" +
                    "    }" +
                    "  } catch(e) {}" +
                    "  return null;" +
                    "}" +
                    "" +
                    // ── 策略 1: 找评论输入框占位符 ──
                    "let el = document.querySelector('[data-e2e=\"comment-input\"], [data-e2e=\"comment-input-area\"]');" +
                    "if (!el) el = deepQuerySelector(document, (el) => {" +
                    "  const ph = (el.getAttribute('placeholder') || '').toLowerCase();" +
                    "  const aria = (el.getAttribute('aria-label') || '').toLowerCase();" +
                    "  const text = (el.textContent || '').trim();" +
                    "  return ph.includes('评论') || ph.includes('comment') || " +
                    "         aria.includes('评论') || aria.includes('comment') || " +
                    "         text === '评论' || text === '说点什么...' || text === '有爱评论，说点儿好听的~';" +
                    "});" +
                    "if (el) { el.click(); return 'placeholder:' + (el.getAttribute('placeholder') || el.textContent); }" +
                    "" +
                    // ── 策略 2: 找 contenteditable div ──
                    "el = document.querySelector('div[contenteditable=\"true\"]');" +
                    "if (!el) el = deepQuerySelector(document, (el) => el.isContentEditable);" +
                    "if (el) { el.click(); el.focus(); return 'contenteditable'; }" +
                    "" +
                    // ── 策略 3: 找 textarea ──
                    "el = document.querySelector('textarea[placeholder*=\"评论\"], textarea[placeholder*=\"comment\" i], textarea');" +
                    "if (el) { el.click(); el.focus(); return 'textarea:' + (el.getAttribute('placeholder') || ''); }" +
                    "" +
                    // ── 策略 4: 点击评论区容器内第一个可交互元素 ──
                    "const containers = document.querySelectorAll('[data-e2e=\"comment-list\"], div[class*=\"comment\"], #douyin-right-container');" +
                    "for (const c of containers) {" +
                    "  const input = c.querySelector('div[contenteditable], textarea, input[type=\"text\"]');" +
                    "  if (input) { input.click(); input.focus(); return 'container-input'; }" +
                    "}" +
                    "" +
                    "return 'not-found';" +
                    "}");
            log.info("   JS 查找结果: {}", result);

            if (!"not-found".equals(result)) {
                log.info("   ✅ 评论框展开成功");
                page.waitForTimeout(1000);
                return;
            }
        } catch (Exception e) {
            log.warn("   JS 展开评论框异常: {}", e.getMessage());
        }

        log.warn("   ⚠️ 所有策略均未找到评论入口");
    }

    /**
     * 在评论编辑器中填入文字。
     * 优先使用 fill（快速），失败则降级为逐字 humanType（反爬）。
     */
    private void fillCommentBox(Page page, String comment) {
        // 策略 1: 尝试快速 fill
        String[] selectors = {
                "div[contenteditable=\"true\"]",
                "textarea[placeholder*=\"评论\" i]",
                "textarea",
                "[data-e2e=\"comment-input\"] div[contenteditable]",
                "[data-e2e=\"comment-input\"] textarea",
        };
        for (String sel : selectors) {
            try {
                log.info("   尝试填入评论 (选择器: {}) ...", sel);
                page.waitForSelector(sel,
                        new Page.WaitForSelectorOptions()
                                .setState(WaitForSelectorState.VISIBLE)
                                .setTimeout(5000));
                page.waitForTimeout(300);

                var box = page.locator(sel).first();
                box.click();
                page.waitForTimeout(200);

                // 优先用 fill（速度快）
                box.fill(comment);
                log.info("   ✅ 评论内容已填入 (fill, 选择器: {})", sel);
                page.waitForTimeout(500);
                return;
            } catch (Exception e) {
                log.info("   选择器 {} 失败，尝试下一个...", sel);
            }
        }

        // 策略 2: JS 直接设置 + 模拟人类打字（键盘逐字输入）
        log.info("   所有 fill 策略失败，尝试 JS + 键盘打字...");
        try {
            page.evaluate("(text) => {" +
                    "  const el = document.querySelector('div[contenteditable=\"true\"], textarea, [data-e2e=\"comment-input\"]');" +
                    "  if (!el) return 'not-found';" +
                    "  el.click();" +
                    "  el.focus();" +
                    "  if (el.isContentEditable) {" +
                    "    el.textContent = '';" +
                    "  } else {" +
                    "    el.value = '';" +
                    "  }" +
                    "  return 'found';" +
                    "}", comment);

            // 逐字键入（模拟人类打字，绕过反爬检测）
            humanType(page, comment);
            return;
        } catch (Exception e) {
            log.warn("   JS 打字也失败: {}", e.getMessage());
        }

        throw new RuntimeException("无法找到抖音评论输入框（已尝试所有策略）");
    }

    /**
     * 模拟人类打字：逐字输入，带随机延迟。
     * 绕开抖音对 fill() / 一次性粘贴的反爬检测。
     */
    private void humanType(Page page, String text) {
        log.info("   ⌨️  逐字输入中 ({} 字)...", text.length());
        try {
            // 先聚焦到可编辑元素
            page.evaluate("() => {" +
                    "  const el = document.querySelector('div[contenteditable=\"true\"], textarea');" +
                    "  if (el) { el.click(); el.focus(); }" +
                    "}");
            page.waitForTimeout(200);

            // 用 Playwright 键盘逐字输入
            for (int i = 0; i < text.length(); i++) {
                String ch = text.substring(i, i + 1);
                page.keyboard().type(ch);
                // 随机延迟 50-200ms
                long delay = 50 + (long) (Math.random() * 150);
                page.waitForTimeout(delay);
            }
            log.info("   ✅ 逐字输入完成");
        } catch (Exception e) {
            log.warn("   ⚠️ 逐字输入异常: {}，尝试 fill 兜底", e.getMessage());
            try {
                var box = page.locator("div[contenteditable=\"true\"]").first();
                box.fill(text);
            } catch (Exception ignored) {}
        }
    }

    /**
     * 找到并点击评论提交按钮。
     */
    private void clickSubmitButton(Page page) {
        page.waitForTimeout(500);

        try {
            Object result = page.evaluate("() => {" +
                    // 策略 1: data-e2e
                    "let btn = document.querySelector('[data-e2e=\"comment-submit\"], [data-e2e=\"comment-send\"], [data-e2e=\"comment-publish\"]');" +
                    "if (btn && btn.offsetParent !== null) {" +
                    "  btn.click();" +
                    "  return 'e2e:' + (btn.textContent.trim().substring(0, 20));" +
                    "}" +
                    // 策略 2: 按钮文本
                    "const keywords = ['发送', '发布', '评论', 'Send', 'Publish', 'Submit'];" +
                    "const buttons = document.querySelectorAll('button, span[role=\"button\"], div[role=\"button\"]');" +
                    "for (const b of buttons) {" +
                    "  if (b.offsetParent === null) continue;" +
                    "  const text = (b.textContent || '').trim();" +
                    "  for (const kw of keywords) {" +
                    "    if (text === kw || text.includes(kw)) {" +
                    "      b.click();" +
                    "      return 'text:' + text.substring(0, 20);" +
                    "    }" +
                    "  }" +
                    "}" +
                    // 策略 3: 在评论区输入框附近找 button
                    "const inputAreas = document.querySelectorAll('div[contenteditable=\"true\"]');" +
                    "for (const input of inputAreas) {" +
                    "  // 向上找，找到包含按钮的父容器" +
                    "  let parent = input.parentElement;" +
                    "  for (let i = 0; i < 5 && parent; i++) {" +
                    "    const btn = parent.querySelector('button');" +
                    "    if (btn && btn.offsetParent !== null) {" +
                    "      btn.click();" +
                    "      return 'nearby:' + (btn.textContent.trim().substring(0, 20));" +
                    "    }" +
                    "    parent = parent.parentElement;" +
                    "  }" +
                    "}" +
                    "return 'not-found';" +
                    "}");
            log.info("   提交按钮查找结果: {}", result);
            if (!"not-found".equals(result)) {
                log.info("   ✅ 评论已提交");
                return;
            }
        } catch (Exception e) {
            log.warn("   JS 提交按钮异常: {}", e.getMessage());
        }

        // 回退：Playwright locator 方式
        String[] submitSelectors = {
                "button:has-text(\"发送\")",
                "button:has-text(\"发布\")",
                "button:has-text(\"评论\")",
                "[data-e2e=\"comment-submit\"]",
                "[data-e2e=\"comment-send\"]",
                "[data-e2e=\"comment-publish\"]",
                "span:has-text(\"发送\")",
                "span:has-text(\"发布\")",
        };
        for (String sel : submitSelectors) {
            try {
                var btn = page.locator(sel).first();
                btn.waitFor(new com.microsoft.playwright.Locator.WaitForOptions().setTimeout(3000));
                if (btn.isEnabled()) {
                    btn.click();
                    log.info("   ✅ 评论已提交 (选择器: {})", sel);
                    return;
                }
            } catch (Exception ignored) {}
        }
        throw new RuntimeException("无法找到抖音提交按钮");
    }

    // ═══════════════════════════════════════════════════════════
    // 调试方法
    // ═══════════════════════════════════════════════════════════

    /**
     * 调试：输出搜索结果页 DOM 结构，辅助排查选择器问题。
     */
    private void debugSearchDOM(Page page) {
        try {
            String info = (String) page.evaluate("() => {" +
                    "const diag = {};" +
                    "diag.url = location.href;" +
                    "diag.title = document.title;" +
                    // 统计视频链接
                    "const links = document.querySelectorAll('a[href*=\"/video/\"]');" +
                    "diag.videoLinkCount = links.length;" +
                    "const first5 = [];" +
                    "for (let i = 0; i < Math.min(5, links.length); i++) {" +
                    "  first5.push({" +
                    "    href: links[i].getAttribute('href')," +
                    "    text: (links[i].textContent || '').trim().substring(0, 60)" +
                    "  });" +
                    "}" +
                    "diag.first5Links = first5;" +
                    // 统计 data-e2e 元素
                    "diag.e2eSearchResult = document.querySelectorAll('[data-e2e*=\"search\"]').length;" +
                    "diag.e2eVideo = document.querySelectorAll('[data-e2e*=\"video\"]').length;" +
                    "diag.e2eFeed = document.querySelectorAll('[data-e2e*=\"feed\"]').length;" +
                    "return JSON.stringify(diag);" +
                    "}");
            log.info("   🔍 搜索页诊断: {}", info);
        } catch (Exception e) {
            log.info("   🔍 搜索页诊断失败: {}", e.getMessage());
        }
    }

    /**
     * 调试：输出评论区域 DOM 结构，辅助排查选择器问题。
     */
    private void debugCommentsDOM(Page page) {
        try {
            String info = (String) page.evaluate("() => {" +
                    "const diag = {};" +
                    // 查找评论区容器
                    "const containers = [" +
                    "  '[data-e2e=\"comment-list\"]'," +
                    "  '[data-e2e=\"comment-container\"]'," +
                    "  'div[class*=\"comment-list\"]'," +
                    "  'div[class*=\"commentList\"]'," +
                    "  'div[class*=\"comment-container\"]'," +
                    "  '#douyin-right-container'" +
                    "];" +
                    "for (const sel of containers) {" +
                    "  const el = document.querySelector(sel);" +
                    "  if (el) {" +
                    "    diag.containerSelector = sel;" +
                    "    diag.containerHTML = el.outerHTML.substring(0, 1500);" +
                    "    break;" +
                    "  }" +
                    "}" +
                    "if (!diag.containerSelector) diag.containerSelector = 'not-found';" +
                    // 统计评论条目
                    "const items1 = document.querySelectorAll('[data-e2e=\"comment-item\"]');" +
                    "const items2 = document.querySelectorAll('div[class*=\"comment-item\"], div[class*=\"commentItem\"]');" +
                    "diag.e2eCommentCount = items1.length;" +
                    "diag.classCommentCount = items2.length;" +
                    // 查找评论输入框
                    "const editable = document.querySelector('div[contenteditable=\"true\"]');" +
                    "diag.hasContentEditable = !!editable;" +
                    "const textarea = document.querySelector('textarea');" +
                    "diag.hasTextarea = !!textarea;" +
                    // 查找提交按钮
                    "const buttons = document.querySelectorAll('button');" +
                    "const btnTexts = [];" +
                    "for (let i = 0; i < Math.min(5, buttons.length); i++) {" +
                    "  btnTexts.push((buttons[i].textContent || '').trim().substring(0, 30));" +
                    "}" +
                    "diag.buttonTexts = btnTexts;" +
                    "return JSON.stringify(diag);" +
                    "}");
            log.info("   🔍 评论区诊断: {}", info);
        } catch (Exception e) {
            log.info("   🔍 评论区诊断失败: {}", e.getMessage());
        }
    }
}
