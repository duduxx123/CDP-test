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
}
