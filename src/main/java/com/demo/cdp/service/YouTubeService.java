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
 * YouTube 自动操作 —— 搜索 + 点第一个视频 + 评论。
 * <p>
 * 复用 {@link ChromeLifecycleService} 的 CDP 连接，与 Bing 搜索共用同一个 Chrome 浏览器实例。
 */
@Service
public class YouTubeService {

    private static final Logger log = LoggerFactory.getLogger(YouTubeService.class);

    private final ChromeLifecycleService chromeService;

    public YouTubeService(ChromeLifecycleService chromeService) {
        this.chromeService = chromeService;
    }

    /**
     * YouTube 搜索结果。
     */
    public record YouTubeResult(boolean success, String videoTitle, String videoUrl, String comment) {
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

    /**
     * 搜索 YouTube 关键词，点进第一个视频并发表评论。
     *
     * @param keyword 搜索关键词
     * @param comment 要发表的评论内容
     * @return 操作结果
     */
    public YouTubeResult searchAndComment(String keyword, String comment) {
        Browser browser = chromeService.getBrowser();
        var context = browser.contexts().getFirst();
        Page page = context.newPage();

        try {
            // ─── 1. 搜索 ───
            searchYouTube(page, keyword);

            // ─── 2. 点击第一个视频 ───
            String videoUrl = clickFirstVideo(page);

            // ─── 3. 等待视频页加载 ───
            page.waitForSelector("#movie_player",
                    new Page.WaitForSelectorOptions().setTimeout(15000));
            String title = page.title();
            log.info("🎬 视频标题: {}", title);

            // ─── 4. 检查登录状态 ───
            if (!isLoggedIn(page)) {
                log.warn("⚠️ 未登录 YouTube，无法评论");
                return new YouTubeResult(false, page.title(),
                        page.url(), "(未登录 YouTube，请先在 Chrome 中登录)");
            }

            // ─── 5. 滚动到评论区 ───
            scrollToComments(page);

            // ─── 6. 发表评论 ───
            postComment(page, comment);

            log.info("✅ YouTube 评论完成: {} → {}", videoUrl, comment);
            return new YouTubeResult(true, title, videoUrl, comment);

        } catch (Exception e) {
            log.error("❌ YouTube 操作失败: {}", e.getMessage(), e);
            return new YouTubeResult(false, "(操作失败)", "", comment);

        } finally {
            // 保留标签页，方便查看效果（展示用）
            log.info("📌 标签页保持打开，可手动查看");
        }
    }

    /**
     * 搜索 YouTube 关键词，点进第一个视频，滚动加载并提取评论。
     *
     * @param keyword 搜索关键词
     * @param count   目标评论条数
     * @return 评论列表结果
     */
    public CommentsResult searchAndFetchComments(String keyword, int count) {
        int target = count > 0 ? count : 10;
        Browser browser = chromeService.getBrowser();
        var context = browser.contexts().getFirst();
        Page page = context.newPage();

        try {
            // ─── 1. 搜索 ───
            searchYouTube(page, keyword);

            // ─── 2. 点击第一个视频 ───
            String videoUrl = clickFirstVideo(page);

            // ─── 3. 等待视频页加载 ───
            page.waitForSelector("#movie_player",
                    new Page.WaitForSelectorOptions().setTimeout(15000));
            String title = page.title();
            log.info("🎬 视频标题: {}", title);

            // ─── 4. 滚动到评论区 ───
            scrollToComments(page);

            // ─── 5. 等待首批评论线程渲染 ───
            try {
                page.waitForSelector("ytd-comment-thread-renderer",
                        new Page.WaitForSelectorOptions().setTimeout(10000));
            } catch (Exception e) {
                log.warn("⚠️ 未检测到评论线程（视频可能关闭了评论）");
                return new CommentsResult(true, title, videoUrl, List.of());
            }

            // ─── 5.5 调试：检查 DOM 结构 ───
            debugCommentThreads(page);

            // ─── 6. 循环滚动 + 提取 ───
            List<CommentItem> collected = new ArrayList<>();
            Set<String> seen = new HashSet<>();       // "author|||text" 去重
            int staleRounds = 0;
            int maxRounds = 20;

            for (int round = 0; round < maxRounds && collected.size() < target; round++) {
                // 提取当前已渲染的评论
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
    // 私有方法
    // ═══════════════════════════════════════════════════════════

    /**
     * 检查是否已登录 YouTube。
     * 通过检测页面上是否存在登录按钮或用户头像来判断。
     */
    private boolean isLoggedIn(Page page) {
        try {
            Object result = page.evaluate("() => {" +
                    // 已登录：右上角有头像按钮
                    "const avatar = document.querySelector('#avatar-btn, #img[src*=\"ytimg\"], yt-img-shadow#img');" +
                    "if (avatar) return true;" +
                    // 未登录：右上角有 "登录" 按钮
                    "const signInBtns = document.querySelectorAll('a[href*=\"ServiceLogin\"], ytd-button-renderer a[href*=\"ServiceLogin\"]');" +
                    "if (signInBtns.length > 0) return false;" +
                    // 不确定，保守返回 false
                    "return false;" +
                    "}");
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 导航到 YouTube 搜索结果页。
     */
    private void searchYouTube(Page page, String query) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://www.youtube.com/results?search_query=" + encoded;
        log.info("🔍 YouTube 搜索: {}", query);
        page.navigate(url);
        // 等待搜索结果列表加载
        page.waitForSelector("ytd-video-renderer",
                new Page.WaitForSelectorOptions().setTimeout(15000));
        log.info("   ✅ 搜索结果已加载 — {}", page.title());
    }

    /**
     * 点击搜索结果中的第一个视频，进入播放页。
     *
     * @return 视频 URL
     */
    private String clickFirstVideo(Page page) {
        var firstVideo = page.locator("ytd-video-renderer").first();

        // 获取视频链接
        var titleLink = firstVideo.locator("#video-title");
        String videoUrl = titleLink.getAttribute("href");
        if (videoUrl != null && !videoUrl.startsWith("http")) {
            videoUrl = "https://www.youtube.com" + videoUrl;
        }
        log.info("🎬 点击第一个视频: {}", videoUrl);

        // 点击标题进入视频页
        titleLink.click();
        log.info("   ✅ 已进入视频页");

        return videoUrl;
    }

    /**
     * 滚动页面触发评论区懒加载。
     * YouTube 的评论区输入框在页面初始加载时不可见——
     * 必须往下滚动足够距离，YouTube 的 JS 才会异步渲染评论编辑器。
     * <p>
     * 采用渐进式滚动：每次滚 500px 后检查评论区是否出现，
     * 一旦出现立即停止，避免滚过头。
     */
    private void scrollToComments(Page page) {
        log.info("📜 滚动页面触发评论区懒加载...");

        for (int i = 0; i < 10; i++) {
            // 先检查评论区是否已经可见
            boolean visible = (boolean) page.evaluate(
                    "() => {" +
                    "  const el = document.querySelector('#comments');" +
                    "  if (!el || el.offsetParent === null) return false;" +
                    "  const rect = el.getBoundingClientRect();" +
                    // 评论区至少有一部分在视口内
                    "  return rect.bottom > 0 && rect.top < window.innerHeight;" +
                    "}");
            if (visible) {
                log.info("   ✅ 评论区已可见 (第 {} 次检查)", i + 1);
                // 把评论区滚动到视口顶部 1/3 处，确保输入框在视野中
                page.evaluate("() => {" +
                        "  const el = document.querySelector('#comments');" +
                        "  if (el) el.scrollIntoView({ behavior: 'instant', block: 'center' });" +
                        "}");
                page.waitForTimeout(1000);
                return;
            }

            // 还没出现，往下滚 500px 触发懒加载
            log.info("   第 {} 次滚动 (评论区尚未可见)", i + 1);
            page.evaluate("() => window.scrollBy(0, 500)");
            page.waitForTimeout(800);
        }

        log.warn("   ⚠️ 滚动 10 次后评论区仍未可见");
    }

    /**
     * 在视频下方发表评论。
     * <p>
     * YouTube 评论框是一个 contenteditable div，交互流程：
     * 1. 点击评论占位符展开评论框
     * 2. 在可编辑区域填入文字
     * 3. 点击提交按钮
     */
    private void postComment(Page page, String comment) {
        log.info("💬 撰写评论: {}", comment);

        // ─── 0. 调试：打印评论区 DOM 结构 ───
        debugCommentsDOM(page);

        // ─── 1. 展开评论输入框 ───
        expandCommentBox(page);

        // ─── 2. 等待 JS 异步加载评论编辑器 + 填入文字 ───
        fillCommentBox(page, comment);

        // ─── 3. 提交评论 ───
        clickSubmitButton(page);

        // 等评论提交完成
        page.waitForTimeout(3000);
    }

    /**
     * 调试：输出评论区 DOM 结构，帮助排查选择器失效问题。
     */
    private void debugCommentsDOM(Page page) {
        try {
            String html = (String) page.evaluate("() => {" +
                    "const el = document.querySelector('#comments, ytd-comments');" +
                    "if (!el) return '#comments NOT FOUND in DOM';" +
                    "return el.outerHTML.substring(0, 2000);" +
                    "}");
            log.info("   🔍 #comments HTML 前段:\n{}", html);
        } catch (Exception e) {
            log.info("   🔍 调试失败: {}", e.getMessage());
        }
    }

    /**
     * 调试：检查评论线程的 DOM 分布情况。
     */
    private void debugCommentThreads(Page page) {
        try {
            String info = (String) page.evaluate("() => {" +
                    "const threads = document.querySelectorAll('ytd-comment-thread-renderer');" +
                    "const vms = document.querySelectorAll('ytd-comment-view-model');" +
                    "let diag = {" +
                    "  'thread count': threads.length," +
                    "  'vm count': vms.length" +
                    "};" +
                    "/* check first ytd-comment-view-model */" +
                    "if (vms.length > 0) {" +
                    "  const vm = vms[0];" +
                    "  diag.vm_hasShadow = !!vm.shadowRoot;" +
                    "  diag.vm_childCount = vm.children.length;" +
                    "  diag.vm_childTags = Array.from(vm.children).map(c => c.tagName).join(',');" +
                    "  diag.vm_innerHTML_500 = vm.innerHTML.substring(0, 500);" +
                    "  /* check for author-text, content-text, published-time-text anywhere in light DOM */" +
                    "  diag.vm_hasAuthor = !!vm.querySelector('#author-text');" +
                    "  diag.vm_hasContent = !!vm.querySelector('#content-text');" +
                    "  diag.vm_hasTime = !!vm.querySelector('#published-time-text');" +
                    "  diag.vm_textContent_200 = vm.textContent.trim().substring(0, 200);" +
                    "}" +
                    "return JSON.stringify(diag);" +
                    "}");
            log.info("   🔍 评论线程诊断: {}", info);
        } catch (Exception e) {
            log.info("   🔍 线程诊断失败: {}", e.getMessage());
        }
    }

    /**
     * 展开评论输入框。
     * 全部通过 JS 直接查找 DOM 元素，不依赖键盘焦点/Tab 导航，
     * 避免因用户在其他地方点击导致焦点错位而失败。
     * 为什么需要 Shadow DOM 穿透？ YouTube 的前端使用 Web Components 技术——组件内部的 DOM 被封装在 Shadow Root（阴影 DOM）里。document.querySelector() 无法穿透 Shadow Root，所以页面上明明显示的评论输入框，用普通选择器就是找不到。
     *
     * deepQuerySelector 的工作原理：
     * 1. 从 root 出发，遍历所有后代元素（querySelectorAll('*')）
     * 2. 对每个元素，先用 predicate 测试是否匹配
     * 3. 检查元素的 el.shadowRoot 属性——如果有 Shadow Root，递归进入再搜索
     * 4. 限制最大深度 5 层（防止无穷递归）
     */
    private void expandCommentBox(Page page) {
        try {
            Object result = page.evaluate("() => {" +
                    // ═══════════════════════════════════════════
                    // 辅助函数：递归穿透 Shadow DOM
                    // YouTube 使用 Web Components，placeholder 在 shadow root 内部，
                    // 普通 document.querySelector 找不到
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
                    // ── 策略 1: 穿透 Shadow DOM 找 placeholder ──
                    "let el = deepQuerySelector(document, (el) => el.id === 'placeholder-area');" +
                    "if (!el) el = deepQuerySelector(document, (el) => el.id === 'simplebox-placeholder');" +
                    "if (!el) el = deepQuerySelector(document, (el) => el.id === 'contenteditable-root');" +
                    "if (el) { el.click(); return 'shadow:' + el.id; }" +
                    // ── 策略 2: 找 aria-label 含 "comment" 的元素 ──
                    "el = deepQuerySelector(document, (el) => {" +
                    "  const a = (el.getAttribute('aria-label') || '').toLowerCase();" +
                    "  return a.includes('comment') || a.includes('コメント');" +
                    "});" +
                    "if (el) { el.click(); return 'aria:' + el.getAttribute('aria-label'); }" +
                    // ── 策略 3: 在 #comments 容器内树遍历 ──
                    "const container = document.querySelector('#comments, ytd-comments');" +
                    "if (container) {" +
                    "  const walker = document.createTreeWalker(container, NodeFilter.SHOW_ELEMENT);" +
                    "  let node; while (node = walker.nextNode()) {" +
                    "    if (node.id && node.offsetParent !== null && " +
                    "        (node.tagName === 'DIV' || node.tagName === 'SPAN' || node.tagName === 'BUTTON')) {" +
                    "      node.click();" +
                    "      return 'walk:' + node.tagName + '#' + node.id;" +
                    "    }" +
                    "  }" +
                    "}" +
                    "return 'not-found';" +
                    "}");
            log.info("   JS 查找结果: {}", result);
            if (!"not-found".equals(result)) {
                log.info("   ✅ 评论框展开成功");
                return;
            }
        } catch (Exception e) {
            log.warn("   JS 展开评论框异常: {}", e.getMessage());
        }

        log.warn("   ⚠️ 所有策略均未找到评论入口");
    }

    /**
     * 等待评论编辑器异步加载完成，填入评论文字。
     */
    private void fillCommentBox(Page page, String comment) {
        String[] commentBoxSelectors = {
                "#contenteditable-root",
                "div[contenteditable=\"true\"][aria-label*=\"comment\" i]",
                "div[contenteditable=\"true\"]"
        };
        for (String sel : commentBoxSelectors) {
            try {
                log.info("   等待评论编辑器: {} ...", sel);
                page.waitForSelector(sel,
                        new Page.WaitForSelectorOptions()
                                .setState(WaitForSelectorState.VISIBLE)
                                .setTimeout(10000));
                page.waitForTimeout(500);
                var box = page.locator(sel).first();
                box.click();
                page.waitForTimeout(300);
                box.fill(comment);
                log.info("   ✅ 评论内容已填入 (选择器: {})", sel);
                return;
            } catch (Exception e) {
                log.info("   选择器 {} 超时，尝试下一个...", sel);
            }
        }
        throw new RuntimeException("无法找到 YouTube 评论输入框（已尝试所有选择器）");
    }

    /**
     * 找到并点击评论提交按钮。
     */
    private void clickSubmitButton(Page page) {
        page.waitForTimeout(500);
        String[] submitSelectors = {
                "#submit-button button[aria-label]",
                "#submit-button button",
                "button[aria-label*=\"Comment\" i]",
                "button[aria-label*=\"コメント\" i]"
        };
        for (String sel : submitSelectors) {
            try {
                var btn = page.locator(sel).first();
                btn.waitFor(new com.microsoft.playwright.Locator.WaitForOptions().setTimeout(5000));
                if (btn.isEnabled()) {
                    btn.click();
                    log.info("   ✅ 评论已提交 (选择器: {})", sel);
                    return;
                }
            } catch (Exception ignored) {}
        }
        throw new RuntimeException("无法找到 YouTube 提交按钮");
    }

    /**
     * 从当前已渲染的评论 DOM 中提取所有评论数据。
     * <p>
     * YouTube 已弃用 Shadow DOM（2025+），评论内容直接在 light DOM 的
     * {@code ytd-comment-view-model} 元素内，选择器为标准 CSS。
     */
    private List<CommentItem> extractVisibleComments(Page page) {
        try {
            @SuppressWarnings("unchecked")
            List<List<String>> raw = (List<List<String>>) page.evaluate("() => {" +
                    "const results = [];" +
                    "const vms = document.querySelectorAll('ytd-comment-view-model');" +
                    "for (const vm of vms) {" +
                    "  try {" +
                    "    const author = (vm.querySelector('#author-text') || {}).textContent || '';" +
                    "    const text   = (vm.querySelector('#content-text') || {}).textContent || '';" +
                    "    const time   = (vm.querySelector('#published-time-text') || {}).textContent || '';" +
                    "    if (text.trim()) results.push([author.trim(), text.trim(), time.trim()]);" +
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
}
