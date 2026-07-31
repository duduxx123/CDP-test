package com.demo.cdp.controller;

import com.demo.cdp.service.DouyinService;
import com.demo.cdp.service.DouyinService.CommentItem;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 抖音自动操作 API。
 *
 * <pre>
 * POST /api/douyin/comment
 * Content-Type: application/json
 * {"keyword": "编程学习", "comment": "讲得很好，已三连！", "index": 1}
 * </pre>
 *
 * <pre>
 * POST /api/douyin/comments
 * Content-Type: application/json
 * {"keyword": "编程学习", "count": 10, "index": 1}
 * </pre>
 */
@RestController
@RequestMapping("/api/douyin")
public class DouyinController {

    private final DouyinService douyinService;

    public DouyinController(DouyinService douyinService) {
        this.douyinService = douyinService;
    }

    /**
     * 在抖音搜索关键词，点进指定视频并发表评论。
     */
    @PostMapping("/comment")
    public CommentResponse comment(@RequestBody CommentRequest request) {

        String keyword = request.keyword() != null && !request.keyword().isEmpty()
                ? request.keyword() : "编程学习";
        String comment = request.comment() != null && !request.comment().isEmpty()
                ? request.comment() : "讲得很好，收藏了！";
        int index = request.index() > 0 ? request.index() : 1;

        var result = douyinService.searchAndComment(keyword, comment, index);
        return new CommentResponse(result.success(), result.videoTitle(), result.videoUrl(), result.comment());
    }

    /**
     * 在抖音搜索关键词，点进指定视频并提取评论。
     */
    @PostMapping("/comments")
    public CommentsResponse fetchComments(@RequestBody CommentsRequest request) {

        String keyword = request.keyword() != null && !request.keyword().isEmpty()
                ? request.keyword() : "编程学习";
        int count = request.count() > 0 ? request.count() : 10;
        int index = request.index() > 0 ? request.index() : 1;

        var result = douyinService.searchAndFetchComments(keyword, count, index);
        return new CommentsResponse(result.success(), result.videoTitle(), result.videoUrl(), result.comments());
    }

    /**
     * 评论请求。
     */
    public record CommentRequest(String keyword, String comment, int index) {
    }

    /**
     * 评论操作响应。
     */
    public record CommentResponse(boolean success, String videoTitle, String videoUrl, String comment) {
    }

    /**
     * 评论获取请求。
     */
    public record CommentsRequest(String keyword, int count, int index) {
    }

    /**
     * 评论获取响应。
     */
    public record CommentsResponse(boolean success, String videoTitle, String videoUrl, List<CommentItem> comments) {
    }

    /**
     * 在抖音搜索关键词，点进指定视频，回复第 N 条一级评论。
     *
     * <pre>
     * POST /api/douyin/reply
     * Content-Type: application/json
     * {"keyword": "编程学习", "replyText": "说得很好！", "videoIndex": 1, "commentIndex": 1}
     * </pre>
     */
    @PostMapping("/reply")
    public ReplyResponse replyToComment(@RequestBody ReplyRequest request) {

        String keyword = request.keyword() != null && !request.keyword().isEmpty()
                ? request.keyword() : "编程学习";
        String replyText = request.replyText() != null && !request.replyText().isEmpty()
                ? request.replyText() : "说得好！";
        int videoIndex = request.videoIndex() > 0 ? request.videoIndex() : 1;
        int commentIndex = request.commentIndex() > 0 ? request.commentIndex() : 1;

        var result = douyinService.searchAndReplyToComment(keyword, replyText, videoIndex, commentIndex);
        return new ReplyResponse(result.success(), result.videoTitle(), result.videoUrl(),
                result.repliedToAuthor(), result.repliedToText(), result.replyText());
    }

    /**
     * 回复请求。
     */
    public record ReplyRequest(String keyword, String replyText, int videoIndex, int commentIndex) {
    }

    /**
     * 回复响应。
     */
    public record ReplyResponse(boolean success, String videoTitle, String videoUrl,
                                String repliedToAuthor, String repliedToText, String replyText) {
    }

    /**
     * 向指定抖音用户发送私信（用户 ID 从评论区 CommentItem.userId 获取）。
     *
     * <pre>
     * POST /api/douyin/dm/send
     * Content-Type: application/json
     * {"userId": "MS4wLjABAAAA...", "message": "你好，看了你的评论..."}
     * </pre>
     */
    @PostMapping("/dm/send")
    public DmSendResponse sendDm(@RequestBody DmSendRequest request) {

        String userId = request.userId() != null && !request.userId().isEmpty()
                ? request.userId() : "";
        String message = request.message() != null && !request.message().isEmpty()
                ? request.message() : "你好！";

        if (userId.isEmpty()) {
            return new DmSendResponse(false, "", "", message, "用户 ID 不能为空");
        }

        var result = douyinService.sendDmByUserId(userId, message);
        return new DmSendResponse(result.success(), result.targetUserName(), result.targetUserId(),
                result.message(), result.errorReason());
    }

    /**
     * 私信发送请求。
     */
    public record DmSendRequest(String userId, String message) {
    }

    /**
     * 私信发送响应。
     */
    public record DmSendResponse(boolean success, String targetUserName, String targetUserId,
                                  String message, String errorReason) {
    }
}
