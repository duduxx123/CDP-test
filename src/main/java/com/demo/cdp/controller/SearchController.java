package com.demo.cdp.controller;

import com.demo.cdp.config.AppConfig;
import com.demo.cdp.model.SearchResult;
import com.demo.cdp.service.BingSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST API —— Bing 搜索。
 *
 * <pre>
 * GET /api/search?q=魔法少女小圆&count=10
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class SearchController {

    private final BingSearchService searchService;
    private final AppConfig config;

    public SearchController(BingSearchService searchService, AppConfig config) {
        this.searchService = searchService;
        this.config = config;
    }

    @GetMapping("/search")
    public SearchResponse search(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int count) {

        String query = q.isEmpty() ? config.search().query() : q;
        int limit = count > 0 ? count : config.search().count();

        List<SearchResult> results = searchService.search(query, limit);
        return new SearchResponse(query, results.size(), results);
    }

    //内部record类
    public record SearchResponse(String query, int count, List<SearchResult> results) {}
}
