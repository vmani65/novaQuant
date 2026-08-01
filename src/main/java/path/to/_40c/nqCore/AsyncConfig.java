package path.to._40c.nqCore;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Dedicated thread pool for post-trade broker enrichment (executed prices, margin,
     * charges — phase 1 of PostTradeService; the DB write phase runs on the trade-exec
     * queue). Single thread keeps Kite API load flat; the deep queue matters because each
     * enrichment can block tens of seconds on fill-retrieval retries and with multiple
     * strategies every open AND close enqueues one task — the old capacity of 10 was a real
     * ceiling that silently rejected post-trade work at the burst.
     * Intentionally separate from the pools used inside calcMarginAndBrokerage for parallel
     * margin API calls — keeping them apart prevents deadlock if all postTradeExecutor
     * threads are waiting on margin futures.
     */
    @Bean("postTradeExecutor")
    public Executor postTradeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("post-trade-");
        executor.initialize();
        return executor;
    }
}
