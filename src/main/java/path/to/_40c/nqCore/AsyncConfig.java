package path.to._40c;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Dedicated thread pool for post-trade calculations (executed prices, margin, PnL).
     * Intentionally separate from ForkJoinPool.commonPool() which is used inside
     * calcMarginAndBrokerage for parallel margin API calls — keeping them on different
     * pools prevents deadlock if all postTradeExecutor threads are waiting on margin futures.
     */
    @Bean("postTradeExecutor")
    public Executor postTradeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("post-trade-");
        executor.initialize();
        return executor;
    }
}
