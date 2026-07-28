package com.demo.cdp;

import com.demo.cdp.config.AppConfig;
import com.demo.cdp.service.BingSearchService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CdpTestDemoApplication {

    //localhost:8080/api/search?q=魔法少女小圆&count=10
    public static void main(String[] args) {
        SpringApplication.run(CdpTestDemoApplication.class, args);
    }

    /**
     * CLI 模式：启动时自动执行一次搜索，打印结果后保持 Web 服务运行。
     */
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "app.mode", havingValue = "cli")
    @org.springframework.context.annotation.Bean
    CommandLineRunner cliRunner(BingSearchService searchService,
                                 AppConfig config) {
        return args -> {
            var results = searchService.search(config.search().query(), config.search().count());
            System.out.println();
            System.out.println("=".repeat(60));
            System.out.println("  Bing 搜索结果 — \"" + config.search().query() + "\"");
            System.out.println("=".repeat(60) + "\n");
            for (int i = 0; i < results.size(); i++) {
                var r = results.get(i);
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
            System.out.println("✅ CLI 搜索完成，Web 服务仍在运行（Ctrl+C 退出）...");
        };
    }
}
