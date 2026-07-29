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
 * 优先使用 Playwright locator API，JS evaluate 仅用于调试和无法用选择器表达的逻辑。
 */
@Service
public class DouyinService {

    private static final Logger log = LoggerFactory.getLogger(DouyinService.class);

    private final ChromeLifecycleService chromeService;

    public DouyinService(ChromeLifecycleService chromeService) {
        this.chromeService = chromeService;
    }

    // ════ 记录类型 ════

    public record DouyinResult(boolean success, String videoTitle, String videoUrl, String comment) {}
    public record CommentItem(String author, String text, String time) {}
    public record CommentsResult(boolean success, String videoTitle, String videoUrl, List<CommentItem> comments) {}
    public record ReplyResult(boolean success, String videoTitle, String videoUrl,
                              String repliedToAuthor, String repliedToText, String replyText) {}

    // ════ 公共方法 ════

    public DouyinResult searchAndComment(String keyword, String comment, int index) {
        int videoIndex = Math.max(1, index);
        Browser browser = chromeService.getBrowser();
        var context = browser.contexts().getFirst();
        Page page = context.newPage();

        try {
            searchDouyin(page, keyword);
            if (isVerificationPage(page)) {
                return new DouyinResult(false, "(验证码拦截)", page.url(), comment);
            }
            String videoUrl = clickVideoByIndex(page, videoIndex);
            waitForVideoPage(page);
            if (isVerificationPage(page)) {
                return new DouyinResult(false, "(验证码拦截)", page.url(), comment);
            }
            if (!isLoggedIn(page)) {
                return new DouyinResult(false, page.title(), page.url(), "(未登录)");
            }
            scrollToComments(page);
            postComment(page, comment);
            log.info("✅ 抖音评论完成: {} → {}", videoUrl, comment);
            return new DouyinResult(true, page.title(), videoUrl, comment);
        } catch (Exception e) {
            log.error("❌ 抖音操作失败: {}", e.getMessage(), e);
            return new DouyinResult(false, "(操作失败)", "", comment);
        } finally {
            log.info("📌 标签页保持打开，可手动查看");
        }
    }

    public CommentsResult searchAndFetchComments(String keyword, int count, int index) {
        int target = count > 0 ? count : 10;
        int videoIndex = Math.max(1, index);
        Browser browser = chromeService.getBrowser();
        var context = browser.contexts().getFirst();
        Page page = context.newPage();

        try {
            searchDouyin(page, keyword);
            if (isVerificationPage(page)) {
                return new CommentsResult(false, "(验证码拦截)", page.url(), List.of());
            }
            String videoUrl = clickVideoByIndex(page, videoIndex);
            waitForVideoPage(page);
            String title = page.title();
            log.info("🎬 视频标题: {}", title);
            if (isVerificationPage(page)) {
                return new CommentsResult(false, "(验证码拦截)", page.url(), List.of());
            }
            scrollToComments(page);
            if (!waitForCommentItems(page)) {
                return new CommentsResult(true, title, videoUrl, List.of());
            }
            debugCommentsDOM(page);

            List<CommentItem> collected = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            int staleRounds = 0;
            int totalRounds = 0;

            // 评论区滚动容器的候选选择器
            String[] scrollContainerSels = {
                    "[data-e2e=\"comment-list\"]",
                    "div[class*=\"comment-list\"]",
                    "div[class*=\"commentList\"]",
                    "#douyin-right-container",
            };

            // 评论条目的选择器（与 extractVisibleComments 保持一致）
            String[] itemSels = {
                    "[data-e2e=\"comment-item\"]",
                    "div[class*=\"comment-item\"]",
                    "div[class*=\"commentItem\"]",
            };

            while (totalRounds < 30 && collected.size() < target) {
                totalRounds++;

                // 1) 先提取当前可见评论
                int beforeCount = countCommentItems(page, itemSels);
                List<CommentItem> batch = extractVisibleComments(page);
                int before = collected.size();
                for (CommentItem item : batch) {
                    if (seen.add(item.author() + "|||" + item.text())) {
                        collected.add(item);
                        if (collected.size() >= target) break;
                    }
                }
                int added = collected.size() - before;
                log.info("   第 {} 轮: 提取 {} 条, 新增 {} 条, 已收集 {}/{} (当前DOM共 {} 条)",
                        totalRounds, batch.size(), added, collected.size(), target, beforeCount);
                if (collected.size() >= target) break;

                if (added == 0) {
                    if (++staleRounds >= 4) { log.info("   连续 {} 轮无新评论，停止", staleRounds); break; }
                } else { staleRounds = 0; }

                // 2) 尝试点击"点击加载更多"按钮
                boolean clickedLoadMore = clickLoadMoreIfPresent(page);
                if (clickedLoadMore) {
                    // 等新评论渲染
                    waitForNewItems(page, itemSels, beforeCount);
                    continue;
                }

                // 3) 滚动评论区容器以触发懒加载
                boolean scrolled = scrollCommentContainer(page, scrollContainerSels);
                if (scrolled) {
                    waitForNewItems(page, itemSels, beforeCount);
                } else {
                    // 容器滚动失败，回退到 window 滚动
                    page.evaluate("() => window.scrollBy(0, 1000)");
                    page.waitForTimeout(1500);
                }
            }

            log.info("✅ 评论提取完成: {} 条", collected.size());
            printComments(title, videoUrl, collected, target);
            return new CommentsResult(true, title, videoUrl, collected);
        } catch (Exception e) {
            log.error("❌ 获取评论失败: {}", e.getMessage(), e);
            return new CommentsResult(false, "(操作失败)", "", List.of());
        } finally {
            log.info("📌 标签页保持打开，可手动查看");
        }
    }

    public ReplyResult searchAndReplyToComment(String keyword, String replyText, int videoIndex, int commentIndex) {
        int vidIdx = Math.max(1, videoIndex);
        int cmtIdx = Math.max(1, commentIndex);
        Browser browser = chromeService.getBrowser();
        var context = browser.contexts().getFirst();
        Page page = context.newPage();

        try {
            searchDouyin(page, keyword);
            if (isVerificationPage(page)) {
                return new ReplyResult(false, "(验证码拦截)", page.url(), "", "", replyText);
            }
            String videoUrl = clickVideoByIndex(page, vidIdx);
            waitForVideoPage(page);
            if (isVerificationPage(page)) {
                return new ReplyResult(false, "(验证码拦截)", page.url(), "", "", replyText);
            }
            if (!isLoggedIn(page)) {
                return new ReplyResult(false, page.title(), page.url(), "(未登录)", "", replyText);
            }
            scrollToComments(page);
            if (!waitForCommentItems(page)) {
                return new ReplyResult(false, page.title(), videoUrl, "(无评论)", "", replyText);
            }
            debugCommentsDOM(page);

            // 定位第 N 条一级评论并点击"回复"
            String[] commentInfo = locateAndClickReply(page, cmtIdx);
            String repliedToAuthor = commentInfo[0];
            String repliedToText = commentInfo[1];
            log.info("🎯 回复目标: @{} → {}", repliedToAuthor, repliedToText);

            // 填写回复内容并提交
            fillReplyInput(page, replyText);
            submitReply(page);

            log.info("✅ 抖音回复评论完成: @{} → {}", repliedToAuthor, replyText);
            return new ReplyResult(true, page.title(), videoUrl, repliedToAuthor, repliedToText, replyText);
        } catch (Exception e) {
            log.error("❌ 抖音回复失败: {}", e.getMessage(), e);
            return new ReplyResult(false, "(操作失败)", "", "", "", replyText);
        } finally {
            log.info("📌 标签页保持打开，可手动查看");
        }
    }

    private static void printComments(String title, String videoUrl, List<CommentItem> comments, int target) {
        System.out.println("=".repeat(60));
        System.out.printf("📺 视频: %s%n", title);
        System.out.printf("🔗 链接: %s%n", videoUrl);
        System.out.printf("💬 评论总数: %d / 目标 %d%n", comments.size(), target);
        System.out.println("-".repeat(60));
        int i = 0;
        for (var c : comments) {
            System.out.printf("[%d] 👤 %s%n", ++i, c.author());
            System.out.printf("    🕒 %s%n", c.time());
            System.out.printf("    💬 %s%n", c.text());
            System.out.println();
        }
        System.out.println("=".repeat(60));
    }

    // ════ 搜索 & 选择视频 ════

    private void searchDouyin(Page page, String keyword) {
        String url = "https://www.douyin.com/search/" +
                URLEncoder.encode(keyword, StandardCharsets.UTF_8) + "?type=video";
        log.info("🔍 抖音搜索: {}", keyword);
        page.navigate(url);

        String[] waitSelectors = {
                "[data-e2e=\"search-result-item\"]",
                "a[href*=\"/video/\"]",
                "div[class*=\"search\"] a[href*=\"/video/\"]",
                "div[class*=\"result\"]",
        };
        boolean loaded = false;
        for (String sel : waitSelectors) {
            try {
                page.waitForSelector(sel, new Page.WaitForSelectorOptions().setTimeout(6000));
                log.info("   ✅ 搜索结果已加载 ({}) — {}", sel, page.title());
                loaded = true;
                break;
            } catch (Exception ignored) {}
        }
        if (!loaded) {
            log.info("   ⏳ 兜底等待...");
            page.waitForTimeout(6000);
        }
        log.info("   📄 title='{}'", page.title());
    }

    /**
     * 纯 Playwright locator：找到第 index 个有效 /video/ 链接 → 直接 navigate。
     */
    private String clickVideoByIndex(Page page, int index) {
        log.info("🎬 定位第 {} 个视频...", index);
        debugSearchDOM(page);

        var allLinks = page.locator("a[href*=\"/video/\"]");
        int total = allLinks.count();

        // 收集有效链接索引（排除 /user/、/music/，必须匹配 /video/数字）
        var validIndices = new ArrayList<Integer>();
        var seenVid = new HashSet<String>();
        for (int i = 0; i < total; i++) {
            String href = allLinks.nth(i).getAttribute("href");
            if (href == null || href.contains("/user/") || href.contains("/music/")) continue;
            String vid = extractVideoId(href);
            if (vid.isEmpty() || !seenVid.add(vid)) continue;
            validIndices.add(i);
        }

        if (validIndices.isEmpty()) {
            throw new RuntimeException("搜索结果中未找到有效视频链接（扫描了 " + total + " 个）");
        }

        int idx = Math.min(index - 1, validIndices.size() - 1);
        int locIdx = validIndices.get(idx);
        String href = allLinks.nth(locIdx).getAttribute("href");

        String text = "";
        try {
            text = allLinks.nth(locIdx).textContent();
            if (text != null) { text = text.trim(); if (text.length() > 80) text = text.substring(0, 80); }
        } catch (Exception ignored) {}

        String videoUrl = normalizeUrl(href);
        log.info("   选中第 {} 个 (共 {} 个有效): {} → {}", index, validIndices.size(), text, videoUrl);
        page.navigate(videoUrl);
        return videoUrl;
    }

    private static String extractVideoId(String url) {
        if (url == null) return "";
        var m = java.util.regex.Pattern.compile("/video/(\\d+)").matcher(url);
        return m.find() ? m.group(1) : "";
    }

    private String normalizeUrl(String url) {
        if (url == null) return null;
        if (url.startsWith("https://") || url.startsWith("http://")) return url;
        if (url.startsWith("//")) return "https:" + url;
        if (url.startsWith("/")) return "https://www.douyin.com" + url;
        return "https://www.douyin.com/" + url;
    }

    private void waitForVideoPage(Page page) {
        try {
            page.waitForURL("**/video/**", new Page.WaitForURLOptions().setTimeout(15000));
            log.info("   ✅ 进入视频页: {}", page.url());
        } catch (Exception e) {
            log.warn("   ⚠️ URL 未变化: {}", page.url());
        }
        try {
            page.waitForSelector("video, [data-e2e=\"video-player\"], div[class*=\"player\"], #douyin-right-container",
                    new Page.WaitForSelectorOptions().setTimeout(8000));
            log.info("   ✅ 视频播放器已渲染");
        } catch (Exception ignored) {}
        page.waitForTimeout(3000);
    }

    // ════ 验证码 / 登录 ════

    private boolean isVerificationPage(Page page) {
        // 纯 Playwright：检查是否存在可见的验证码元素
        String[] captchaSelectors = {
                ".secsdk-captcha-drag-wrapper",
                ".captcha_verify_container",
                "#captcha-verify-image",
                ".captcha_verify_img--wrapper",
        };
        for (String sel : captchaSelectors) {
            try {
                if (page.locator(sel).first().isVisible()) {
                    log.warn("   🛡️ 检测到验证码 ({})！", sel);
                    return true;
                }
            } catch (Exception ignored) {}
        }

        // 登录弹窗拦截
        try {
            var modal = page.locator("[class*=\"login-mask\"]").first();
            if (modal.count() > 0 && modal.isVisible()) return true;
        } catch (Exception ignored) {}

        // 检查 URL 是否被重定向到验证域名
        String url = page.url().toLowerCase();
        return url.contains("verify") || url.contains("captcha");
    }

    private boolean isLoggedIn(Page page) {
        // 纯 Playwright locator: 检查是否有用户头像（已登录标志）
        String[] loggedInSelectors = {
                "[data-e2e=\"user-avatar\"]",
                "img[class*=\"avatar\"]",
                "[data-e2e=\"profile-icon\"]",
        };
        for (String sel : loggedInSelectors) {
            try {
                if (page.locator(sel).first().isVisible()) {
                    log.info("✅ 已登录抖音");
                    return true;
                }
            } catch (Exception ignored) {}
        }

        // 检查是否有显式登录按钮
        try {
            var loginBtn = page.locator("span:text-is(\"登录\"), button:has-text(\"登录\")").first();
            if (loginBtn.count() > 0 && loginBtn.isVisible()) return false;
        } catch (Exception ignored) {}

        // 在视频页上保守返回 true
        return page.url().contains("/video/");
    }

    // ════ 评论区 ════

    private void scrollToComments(Page page) {
        log.info("📜 滚动到评论区...");
        String[] areaSelectors = {
                "[data-e2e=\"comment-list\"]",
                "[data-e2e=\"comment-container\"]",
                "div[class*=\"comment-list\"]",
                "div[class*=\"commentList\"]",
                "div[class*=\"comment-container\"]",
                "#douyin-right-container",
        };

        for (int i = 0; i < 15; i++) {
            // Playwright locator 检查评论区是否可见
            for (String sel : areaSelectors) {
                var el = page.locator(sel).first();
                if (el.count() > 0 && el.isVisible()) {
                    try {
                        el.evaluate("node => node.scrollIntoView({behavior:'instant',block:'center'})");
                    } catch (Exception ignored) {}
                    log.info("   ✅ 评论区已可见 (第 {} 次, {})", i + 1, sel);
                    page.waitForTimeout(1000);
                    return;
                }
            }
            log.info("   第 {} 次滚动...", i + 1);
            page.evaluate("() => window.scrollBy(0, 500)");
            page.waitForTimeout(800);
        }
        log.warn("   ⚠️ 评论区未出现");
    }

    private boolean waitForCommentItems(Page page) {
        String[] selectors = {
                "[data-e2e=\"comment-item\"]",
                "div[class*=\"comment-item\"]",
                "div[class*=\"commentItem\"]",
        };
        for (String sel : selectors) {
            try {
                page.waitForSelector(sel, new Page.WaitForSelectorOptions().setTimeout(8000));
                log.info("   ✅ 评论条目已渲染 ({})", sel);
                return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    // ════ 评论提取 ════

    /** JS 只做 DOM 导航，字符串清洗全部在 Java 侧。 */
    @SuppressWarnings("unchecked")
    private List<CommentItem> extractVisibleComments(Page page) {
        try {
            var items = page.locator("[data-e2e=\"comment-item\"]");
            if (items.count() == 0) {
                items = page.locator("div[class*=\"comment-item\"], div[class*=\"commentItem\"]");
            }
            if (items.count() == 0) return List.of();

            // JS: 只做 DOM 查询，返回原始字段 [authorFromLink, fullTextContent, timeFromRegex, bodyText]
            List<List<String>> raw = (List<List<String>>) page.evaluate(
                "() => {" +
                "  var all = document.querySelectorAll('[data-e2e=\"comment-item\"], " +
                "    div[class*=\"comment-item\"], div[class*=\"commentItem\"]');" +
                "  return Array.from(all).map(function(item) {" +
                // 跳过嵌套子回复
                "    var p = item.parentElement;" +
                "    while (p && p !== document.body) {" +
                "      if (p.getAttribute && p.getAttribute('data-e2e') === 'comment-item') return null;" +
                "      p = p.parentElement;" +
                "    }" +
                "    var full = (item.textContent || '').trim();" +
                // 作者链接
                "    var author = '';" +
                "    var ua = item.querySelector('a[href*=\"/user/\"]');" +
                "    if (ua) author = (ua.textContent || '').trim();" +
                // 时间
                "    var tm = '';" +
                "    var m = full.match(/\\d+\\s*(分钟前|小时前|天前|秒前|周前|月前|年前)|刚刚|\\d+-\\d+-\\d+/);" +
                "    if (m) tm = m[0];" +
                // 正文容器文本
                "    var body = '';" +
                "    var be = item.querySelector('[data-e2e=\"comment-content\"], " +
                "      [class*=\"comment-content\"], [class*=\"FduGc\"]');" +
                "    if (be) body = (be.textContent || '').trim();" +
                "    return [author, full, tm, body];" +
                "  }).filter(function(r) { return r !== null; });" +
                "}");

            if (raw == null) return List.of();

            // Java 侧：字符串清洗 + 过滤
            var noisePattern = java.util.regex.Pattern.compile(
                    "^\\d+\\s*(分享回复|条回复)$|^展开\\d+条回复$");
            var replyPattern = java.util.regex.Pattern.compile(
                    "^回复\\s*@?\\S*:?\\s*");
            var atPattern = java.util.regex.Pattern.compile(
                    "^@\\S+\\s*");
            var nameTextPattern = java.util.regex.Pattern.compile(
                    "^(\\S+?)\\.\\.\\.(.+)");  // "Name...actualText"

            List<CommentItem> result = new ArrayList<>();
            for (List<String> row : raw) {
                if (row.size() < 4) continue;
                String author = nz(row.get(0));
                String fullText = nz(row.get(1));
                String time = nz(row.get(2));
                String bodyText = nz(row.get(3));

                // 过滤 "X分享回复" / "展开X条回复"
                if (noisePattern.matcher(fullText).matches()) continue;

                // 选正文：bodyText 优先，否则从 fullText 剥离作者+时间+尾部噪音
                String text;
                if (!bodyText.isEmpty()) {
                    text = bodyText;
                    // body 也可能以作者名开头，剥离
                    if (!author.isEmpty() && text.startsWith(author)) {
                        text = text.substring(author.length()).trim();
                    }
                } else {
                    text = fullText;
                    if (!author.isEmpty() && text.startsWith(author)) {
                        text = text.substring(author.length()).trim();
                    }
                    // 从时间位置截断（去掉时间、地点、回复数等元数据）
                    if (!time.isEmpty()) {
                        int ti = text.indexOf(time);
                        if (ti > 0) text = text.substring(0, ti).trim();
                    }
                }

                // 清理 "回复 @xxx:" / "@xxx "
                text = replyPattern.matcher(text).replaceFirst("").trim();
                text = atPattern.matcher(text).replaceFirst("").trim();

                // 作者名兜底：尝试 "Name...text" 格式（作者可能只在 fullText 里）
                if (author.isEmpty()) {
                    var nm = nameTextPattern.matcher(text);
                    if (nm.find()) {
                        author = nm.group(1);
                        text = nm.group(2).trim();
                    } else {
                        // bodyText 里没有作者，从 fullText 提取作者名
                        nm = nameTextPattern.matcher(fullText);
                        if (nm.find()) author = nm.group(1);
                    }
                }

                // 最终验证
                if (noisePattern.matcher(text).matches()) continue;
                if (text.isEmpty() && author.isEmpty()) continue;

                result.add(new CommentItem(author, text, time));
            }
            return result;
        } catch (Exception e) {
            log.warn("   ⚠️ 评论提取失败: {}", e.getMessage());
            return List.of();
        }
    }

    private static String nz(String s) { return s != null ? s : ""; }

    // ════ 异步加载更多评论 ════

    /** 统计当前页面上评论条目的数量 */
    private int countCommentItems(Page page, String[] selectors) {
        for (String sel : selectors) {
            try {
                int count = page.locator(sel).count();
                if (count > 0) return count;
            } catch (Exception ignored) {}
        }
        return 0;
    }

    /** 点击"点击加载更多"按钮，先用 Playwright 定位，失败则用 JS 全局搜索。 */
    private boolean clickLoadMoreIfPresent(Page page) {
        String[] loadMoreTexts = {"点击加载更多", "加载更多", "展开更多评论", "查看全部评论"};
        for (String text : loadMoreTexts) {
            // Playwright: span/button/div 均可
            for (String tag : new String[]{"span", "button", "div"}) {
                try {
                    var btn = page.locator(tag + ":has-text(\"" + text + "\")").first();
                    if (btn.count() > 0 && btn.isVisible()) {
                        log.info("   🔘 点击 '{}' ({} 定位)", text, tag);
                        btn.click();
                        page.waitForTimeout(2500);
                        return true;
                    }
                } catch (Exception ignored) {}
            }
        }
        // JS 兜底：遍历所有元素找匹配文本的 clickable 元素
        try {
            Object jsResult = page.evaluate(
                "() => {" +
                "  var texts = ['点击加载更多','加载更多','展开更多评论','查看全部评论'];" +
                "  for (var i = 0; i < texts.length; i++) {" +
                "    var all = document.querySelectorAll('span, button, div');" +
                "    for (var j = 0; j < all.length; j++) {" +
                "      var el = all[j];" +
                "      if (el.children.length > 0) continue;" +          // 只取叶子节点
                "      if ((el.textContent||'').trim() === texts[i]) {" +
                "        el.click();" +
                "        return JSON.stringify({text:texts[i], tag:el.tagName});" +
                "      }" +
                "    }" +
                "  }" +
                "  return null;" +
                "}");
            if (jsResult != null) {
                log.info("   🔘 JS 点击: {}", jsResult);
                page.waitForTimeout(2500);
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** 把最后一个评论条目滚到视野底部，触发懒加载。 */
    private boolean scrollCommentContainer(Page page, String[] containerSels) {
        // 方案A: 把最后一个 comment-item 滚到视野底部（最可靠）
        try {
            Object result = page.evaluate(
                "() => {" +
                // 找到评论可见区域内的最后一个 comment-item
                "  var items = document.querySelectorAll('[data-e2e=\"comment-item\"]');" +
                "  if (items.length === 0) return null;" +
                "  var last = items[items.length - 1];" +
                "  last.scrollIntoView({behavior:'instant', block:'end'});" +
                "  return JSON.stringify({method:'scrollIntoView', index:items.length-1});" +
                "}");
            if (result != null) {
                log.info("   📜 scrollIntoView 最后一个 comment-item: {}", result);
                page.waitForTimeout(1500);
                return true;
            }
        } catch (Exception ignored) {}

        // 方案B: 在容器内 mouse wheel 滚动
        for (String sel : containerSels) {
            try {
                var container = page.locator(sel).first();
                if (container.count() > 0) {
                    var box = container.boundingBox();
                    if (box != null) {
                        double cx = box.x + box.width / 2;
                        double cy = box.y + box.height / 2;
                        page.mouse().move(cx, cy);
                        page.mouse().wheel(0, 1000);
                        log.info("   📜 mouse.wheel 在容器 {} ({}, {})", sel, cx, cy);
                        page.waitForTimeout(1500);
                        return true;
                    }
                }
            } catch (Exception ignored) {}
        }

        // 方案C: 回退到 window 滚动
        page.evaluate("() => window.scrollBy(0, 1200)");
        page.waitForTimeout(1500);
        return false;
    }

    /** 等待新的评论条目出现（DOM 数量增加或超时） */
    private void waitForNewItems(Page page, String[] itemSels, int beforeCount) {
        for (int w = 0; w < 15; w++) {
            page.waitForTimeout(400);
            int now = countCommentItems(page, itemSels);
            if (now > beforeCount) {
                log.info("   ✅ 新评论已渲染 ({} → {})", beforeCount, now);
                return;
            }
        }
        log.info("   ⏳ 等待超时 ({} 条未变)", beforeCount);
    }

    // ════ 发表评论 ════

    private void postComment(Page page, String comment) {
        log.info("💬 撰写评论: {}", comment);
        debugCommentsDOM(page);
        expandCommentBox(page);
        fillCommentBox(page, comment);
        clickSubmitButton(page);
        page.waitForTimeout(3000);
    }

    private void expandCommentBox(Page page) {
        log.info("   📝 展开评论输入框...");

        // 策略 1: data-e2e
        try {
            var el = page.locator("[data-e2e=\"comment-input\"], [data-e2e=\"comment-input-area\"]").first();
            if (el.count() > 0 && el.isVisible()) { el.click(); page.waitForTimeout(1000); return; }
        } catch (Exception ignored) {}

        // 策略 2: contenteditable
        try {
            var el = page.locator("div[contenteditable=\"true\"]").first();
            if (el.count() > 0) { el.click(); page.waitForTimeout(1000); return; }
        } catch (Exception ignored) {}

        // 策略 3: textarea
        try {
            var el = page.locator("textarea").first();
            if (el.count() > 0) { el.click(); page.waitForTimeout(1000); return; }
        } catch (Exception ignored) {}

        // 策略 4: 点评论区容器
        try {
            page.locator("div[class*=\"comment\"]").first()
                    .click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(3000));
            page.waitForTimeout(1500);
        } catch (Exception ignored) {}
    }

    private void fillCommentBox(Page page, String comment) {
        String[] selectors = {
                "div[contenteditable=\"true\"]",
                "textarea",
                "[data-e2e=\"comment-input\"] div[contenteditable]",
        };
        for (String sel : selectors) {
            try {
                page.waitForSelector(sel,
                        new Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(4000));
                page.waitForTimeout(300);
                var box = page.locator(sel).first();
                box.click();
                page.waitForTimeout(200);
                box.fill(comment);
                log.info("   ✅ 评论已填入 ({})", sel);
                page.waitForTimeout(500);
                return;
            } catch (Exception ignored) {}
        }

        // 兜底: 键盘逐字输入
        log.info("   使用键盘逐字输入...");
        humanType(page, comment);
    }

    private void humanType(Page page, String text) {
        try {
            page.locator("div[contenteditable=\"true\"]").first().click();
            page.waitForTimeout(200);
            for (int i = 0; i < text.length(); i++) {
                page.keyboard().type(text.substring(i, i + 1));
                page.waitForTimeout(50 + (long) (Math.random() * 150));
            }
            log.info("   ✅ 逐字输入完成");
        } catch (Exception e) {
            throw new RuntimeException("无法输入评论", e);
        }
    }

    private void clickSubmitButton(Page page) {
        page.waitForTimeout(1000);

        // 策略1: 评论区 contenteditable 里按 Enter（抖音最可靠的提交方式）
        String[] commentBoxSels = {
                "[data-e2e=\"comment-list\"] div[contenteditable=\"true\"]",
                "div[class*=\"comment-mainContent\"] div[contenteditable=\"true\"]",
                "div[class*=\"comment-container\"] div[contenteditable=\"true\"]",
        };
        for (String sel : commentBoxSels) {
            try {
                var box = page.locator(sel).first();
                if (box.count() > 0) {
                    box.press("Enter");
                    log.info("   ✅ Enter 已发送 ({})", sel);
                    page.waitForTimeout(2000);
                    return;
                }
            } catch (Exception ignored) {}
        }

        // 策略2: 所有 contenteditable 的最后一个（评论框通常在搜索框后面）
        try {
            var all = page.locator("div[contenteditable=\"true\"]");
            if (all.count() > 0) {
                all.last().press("Enter");
                log.info("   ✅ Enter 已发送 (last contenteditable)");
                page.waitForTimeout(2000);
                return;
            }
        } catch (Exception ignored) {}

        // 策略3: 评论区容器内找发送/发布按钮
        String[] containers = {"[data-e2e=\"comment-list\"]", "div[class*=\"comment-mainContent\"]",
                "div[class*=\"comment-container\"]", "#douyin-right-container"};
        for (String container : containers) {
            for (String t : new String[]{"发送", "发布"}) {
                try {
                    var btn = page.locator(container + " button:text-is(\"" + t + "\"), " +
                            container + " span:text-is(\"" + t + "\"), " +
                            container + " [class*=\"submit\"], " +
                            container + " [class*=\"send\"]").first();
                    if (btn.count() > 0 && btn.isVisible() && btn.isEnabled()) {
                        btn.evaluate("el => el.click()");  // 原生 click，不走 Playwright 事件链
                        log.info("   ✅ 原生点击 ({}: {})", container, t);
                        page.waitForTimeout(2000);
                        return;
                    }
                } catch (Exception ignored) {}
            }
        }

        // 策略4: 全局 Enter
        log.info("   ⌨️ 全局 Enter");
        page.keyboard().press("Enter");
        page.waitForTimeout(2000);
    }

    // ════ 调试方法 ════

    private void debugSearchDOM(Page page) {
        try {
            String info = (String) page.evaluate(
                "() => JSON.stringify({" +
                "  url:location.href, title:document.title," +
                "  videoLinkCount:document.querySelectorAll('a[href*=\"/video/\"]').length," +
                "  first5:Array.from(document.querySelectorAll('a[href*=\"/video/\"]')).slice(0,5)" +
                "    .map(a=>({href:a.getAttribute('href'),text:(a.textContent||'').trim().substring(0,60)}))," +
                "  bodyStart:(document.body.textContent||'').trim().substring(0,300)" +
                "})");
            log.info("   🔍 搜索页: {}", info);
        } catch (Exception e) {
            log.info("   🔍 搜索页诊断失败: {}", e.getMessage());
        }
    }

    private void debugCommentsDOM(Page page) {
        try {
            String info = (String) page.evaluate(
                "() => JSON.stringify({" +
                "  e2e:document.querySelectorAll('[data-e2e=\"comment-item\"]').length," +
                "  class:document.querySelectorAll('div[class*=\"comment-item\"], div[class*=\"commentItem\"]').length," +
                "  editable:!!document.querySelector('div[contenteditable=\"true\"]')," +
                "  textarea:!!document.querySelector('textarea')," +
                "  buttons:Array.from(document.querySelectorAll('button')).slice(0,8)" +
                "    .map(b=>(b.textContent||'').trim().substring(0,30))" +
                "})");
            log.info("   🔍 评论区: {}", info);
        } catch (Exception e) {
            log.info("   🔍 评论区诊断失败: {}", e.getMessage());
        }
    }

    // ════ 回复评论 ════

    /**
     * 定位第 N 条一级评论，点击"回复"按钮，返回 [author, text]。
     * 多策略 fallback：Playwright → JS 全局扫描 → hover 后点击。
     */
    private String[] locateAndClickReply(Page page, int commentIndex) {
        log.info("🔍 定位第 {} 条一级评论的回复按钮...", commentIndex);

        // 1) 找到第 N 个一级评论的 DOM 索引（需要 JS 过滤嵌套子回复）
        int domIndex = findTopLevelCommentIndex(page, commentIndex);
        var target = page.locator("[data-e2e=\"comment-item\"]").nth(domIndex);

        // 2) 提取作者和正文（author 需要 JS 从 DOM 拿，Playwright nth().textContent 不可靠）
        String author = extractAuthorByIndex(page, domIndex);
        String commentText = extractCommentText(target);
        log.info("   📝 @{}: {}", author, commentText);

        // 3) 点击回复按钮
        if (!clickReplyButton(target)) {
            target.hover();
            page.waitForTimeout(800);
            if (!clickReplyButton(target)) {
                throw new RuntimeException("无法找到第 " + commentIndex + " 条评论的回复按钮");
            }
        }

        page.waitForTimeout(1500);
        return new String[]{author, commentText};
    }

    /** 用 page.evaluate + 索引提取作者名（比 nth().locator.textContent() 可靠） */
    private String extractAuthorByIndex(Page page, int domIndex) {
        try {
            Object name = page.evaluate(
                "(idx) => {" +
                "  var item = document.querySelectorAll('[data-e2e=\"comment-item\"]')[idx];" +
                "  if (!item) return '';" +
                // 取整个 item 的 textContent 的第一个词作为作者名
                "  var full = (item.textContent||'').trim();" +
                // 作者名通常是 textContent 中第一个 "..." 之前的部分
                "  var dotIdx = full.indexOf('...');" +
                "  if (dotIdx > 0) return full.substring(0, dotIdx).trim();" +
                // 否则取 fullText 的第一个词（以空格/标点分隔）
                "  return full.split(/[\\s,，。！!、]+/)[0] || '';" +
                "}", domIndex);
            return name != null ? name.toString().trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** 找到第 targetN 个一级评论的 DOM 索引（跳过嵌套在父评论中的子回复） */
    private int findTopLevelCommentIndex(Page page, int targetN) {
        Object result = page.evaluate(
            "(n) => {" +
            "  var all = document.querySelectorAll('[data-e2e=\"comment-item\"]');" +
            "  var count = 0;" +
            "  for (var i = 0; i < all.length; i++) {" +
            "    var p = all[i].parentElement, nested = false;" +
            "    while (p && p !== document.body) {" +
            "      if (p.getAttribute && p.getAttribute('data-e2e') === 'comment-item')" +
            "        { nested = true; break; }" +
            "      p = p.parentElement;" +
            "    }" +
            "    if (!nested) count++;" +
            "    if (count === n) return i;" +
            "  }" +
            "  return -1;" +
            "}", targetN);
        int idx = result != null ? ((Number) result).intValue() : -1;
        if (idx < 0) {
            throw new RuntimeException("只有 " + (idx == -1 ? "?" : String.valueOf(idx)) + " 条一级评论，无法定位第 " + targetN + " 条");
        }
        return idx;
    }

    /** 在评论 item 内提取评论文本 */
    private String extractCommentText(com.microsoft.playwright.Locator item) {
        String[] sels = {
                "[data-e2e=\"comment-content\"]",
                "[class*=\"comment-content\"]",
                "[class*=\"commentContent\"]",
                "[class*=\"FduGc\"]",
        };
        for (String sel : sels) {
            try {
                var el = item.locator(sel).first();
                if (el.count() > 0) {
                    String t = el.textContent();
                    if (t != null && !t.isBlank()) {
                        t = t.trim();
                        return t.length() > 120 ? t.substring(0, 120) : t;
                    }
                }
            } catch (Exception ignored) {}
        }
        return "";
    }

    /** 在评论 item 内点击"回复"按钮 */
    private boolean clickReplyButton(com.microsoft.playwright.Locator item) {
        String[] replySels = {
                "span:text-is(\"回复\")",
                "button:text-is(\"回复\")",
                ":text-is(\"回复\")",              // 任意元素精确匹配 "回复"
                "[class*=\"reply-btn\"]",
                "[class*=\"replyButton\"]",
                "span:has-text(\"回复\")",
        };
        for (String sel : replySels) {
            try {
                var btn = item.locator(sel).first();
                if (btn.count() > 0) {
                    // 最后几个选择器用 force click
                    boolean force = sel.startsWith("[class") || sel.startsWith("span:has");
                    btn.click(new com.microsoft.playwright.Locator.ClickOptions().setForce(force));
                    log.info("   ✅ 已点击回复按钮 ({})", sel);
                    return true;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    /**
     * 填写回复内容。点击"回复"后会出现第二个 contenteditable，用这个新出现的输入框。
     */
    private void fillReplyInput(Page page, String replyText) {
        log.info("   ✍️ 填写回复: {}", replyText);

        // 等待第二个 contenteditable 出现（点击"回复"后 Douyin 可能异步创建输入框）
        int contentEditableCount = 0;
        for (int w = 0; w < 10; w++) {
            page.waitForTimeout(400);
            try {
                contentEditableCount = page.locator("div[contenteditable=\"true\"]").count();
                if (contentEditableCount >= 2) break;
            } catch (Exception ignored) {}
        }
        log.info("   contenteditable 数量: {}", contentEditableCount);

        // 策略1: 找到新增的 contenteditable（最后一个就是回复框）
        try {
            var all = page.locator("div[contenteditable=\"true\"]");
            int count = all.count();
            if (count >= 2) {
                var replyBox = all.last();
                replyBox.click();
                page.waitForTimeout(300);
                replyBox.fill(replyText);
                log.info("   ✅ 回复已填入 (第 {} 个 contenteditable)", count);
                page.waitForTimeout(500);
                return;
            }
            if (count == 1) {
                // 只有1个，可能就是回复框（主评论框没出现）
                var box = all.first();
                box.click();
                page.waitForTimeout(300);
                box.fill(replyText);
                log.info("   ✅ 回复已填入 (唯一 contenteditable)");
                page.waitForTimeout(500);
                return;
            }
        } catch (Exception ignored) {}

        // 策略2: 在评论区容器内找 contenteditable
        try {
            String[] containers = {"[data-e2e=\"comment-list\"]", "div[class*=\"comment-mainContent\"]",
                    "div[class*=\"comment-container\"]", "#douyin-right-container"};
            for (String container : containers) {
                try {
                    var box = page.locator(container + " div[contenteditable=\"true\"]").last();
                    if (box.count() > 0) {
                        box.click();
                        page.waitForTimeout(300);
                        box.fill(replyText);
                        log.info("   ✅ 回复已填入 (容器: {})", container);
                        page.waitForTimeout(500);
                        return;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        // 策略3: textarea 兜底
        try {
            var ta = page.locator("textarea").last();
            if (ta.count() > 0) {
                ta.click();
                page.waitForTimeout(300);
                ta.fill(replyText);
                log.info("   ✅ 回复已填入 (textarea)");
                page.waitForTimeout(500);
                return;
            }
        } catch (Exception ignored) {}

        // 策略4: 键盘逐字输入兜底
        log.info("   使用键盘逐字输入...");
        humanType(page, replyText);
    }

    /**
     * 提交回复：Enter → 发送按钮 → 全局 Enter。
     */
    private void submitReply(Page page) {
        page.waitForTimeout(800);
        log.info("   🚀 提交回复...");

        // 策略1: 最后一个 contenteditable 里按 Enter（抖音最可靠的提交方式）
        try {
            var all = page.locator("div[contenteditable=\"true\"]");
            int count = all.count();
            if (count > 0) {
                all.last().press("Enter");
                log.info("   ✅ Enter 已发送 (第 {} 个 contenteditable)", count);
                page.waitForTimeout(2000);
                return;
            }
        } catch (Exception ignored) {}

        // 策略2: 在所有 contenteditable 里尝试 Enter
        String[] commentBoxSels = {
                "[data-e2e=\"comment-list\"] div[contenteditable=\"true\"]",
                "div[class*=\"comment-mainContent\"] div[contenteditable=\"true\"]",
                "div[class*=\"comment-container\"] div[contenteditable=\"true\"]",
        };
        for (String sel : commentBoxSels) {
            try {
                var boxes = page.locator(sel);
                if (boxes.count() > 0) {
                    boxes.last().press("Enter");
                    log.info("   ✅ Enter 已发送 ({})", sel);
                    page.waitForTimeout(2000);
                    return;
                }
            } catch (Exception ignored) {}
        }

        // 策略3: 评论区内的"发送"/"发布"按钮
        String[] containers = {"[data-e2e=\"comment-list\"]", "div[class*=\"comment-mainContent\"]",
                "div[class*=\"comment-container\"]", "#douyin-right-container"};
        for (String container : containers) {
            for (String t : new String[]{"发送", "发布"}) {
                try {
                    var btns = page.locator(container + " button:text-is(\"" + t + "\"), " +
                            container + " span:text-is(\"" + t + "\"), " +
                            container + " [class*=\"submit\"], " +
                            container + " [class*=\"send\"]");
                    // 取最后一个按钮（回复的发送按钮通常是后出现的）
                    if (btns.count() > 0) {
                        var btn = btns.last();
                        if (btn.isVisible() && btn.isEnabled()) {
                            btn.evaluate("el => el.click()");
                            log.info("   ✅ 原生点击发送按钮 ({}: {}, index={})", container, t, btns.count() - 1);
                            page.waitForTimeout(2000);
                            return;
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        // 策略4: 全局 Enter 兜底
        log.info("   ⌨️ 全局 Enter 兜底");
        page.keyboard().press("Enter");
        page.waitForTimeout(2000);
    }

    /** 调试回复相关 DOM 结构 */
    private void debugReplyDOM(Page page, int commentIndex) {
        try {
            int totalItems = page.locator("[data-e2e=\"comment-item\"]").count();
            int editableCount = page.locator("div[contenteditable=\"true\"]").count();
            int replyBtns = page.locator(":text-is(\"回复\")").count();
            log.info("   🔍 回复调试: items={}, contenteditable={}, replyBtns={}, targetIdx={}",
                    totalItems, editableCount, replyBtns, commentIndex);
        } catch (Exception e) {
            log.info("   🔍 回复调试失败: {}", e.getMessage());
        }
    }
}
