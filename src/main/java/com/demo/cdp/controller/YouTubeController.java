package com.demo.cdp.controller;

import com.demo.cdp.service.YouTubeService;
import com.demo.cdp.service.YouTubeService.CommentItem;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * YouTube 自动操作 API。
 *
 * <pre>
 * POST /api/youtube/comment
 * Content-Type: application/json
 * {"keyword": "魔法少女とチョコレート", "comment": "いいね！"}
 * </pre>
 */
@RestController
@RequestMapping("/api/youtube")
public class YouTubeController {

    private final YouTubeService youTubeService;

    public YouTubeController(YouTubeService youTubeService) {
        this.youTubeService = youTubeService;
    }

    @PostMapping("/comment")
    public CommentResponse comment(@RequestBody CommentRequest request) {

        String keyword = request.keyword() != null && !request.keyword().isEmpty()
                ? request.keyword() : "魔法少女とチョコレート";
        String comment = request.comment() != null && !request.comment().isEmpty()
                ? request.comment() : "素敵な動画ですね！";

        var result = youTubeService.searchAndComment(keyword, comment);
        return new CommentResponse(result.success(), result.videoTitle(), result.videoUrl(), result.comment());
    }

    /**
     * 获取 YouTube 视频评论。
     *
     * <pre>
     * POST /api/youtube/comments
     * Content-Type: application/json
     * {"keyword": "魔法少女とチョコレート", "count": 10}
     * </pre>
     */
    @PostMapping("/comments")
    public CommentsResponse fetchComments(@RequestBody CommentsRequest request) {

        String keyword = request.keyword() != null && !request.keyword().isEmpty()
                ? request.keyword() : "魔法少女とチョコレート";
        int count = request.count() > 0 ? request.count() : 10;

        var result = youTubeService.searchAndFetchComments(keyword, count);
        return new CommentsResponse(result.success(), result.videoTitle(), result.videoUrl(), result.comments());
    }

    /**
     * 评论请求。
     */
    public record CommentRequest(String keyword, String comment) {
    }

    /**
     * 评论操作响应。
     */
    public record CommentResponse(boolean success, String videoTitle, String videoUrl, String comment) {
    }

    /**
     * 评论获取请求。
     */
    public record CommentsRequest(String keyword, int count) {
    }

    /**
     * 评论获取响应。
     */
    public record CommentsResponse(boolean success, String videoTitle, String videoUrl, List<CommentItem> comments) {
    }
}
