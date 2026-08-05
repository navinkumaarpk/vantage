package io.vantage.loganalyzer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Dedicated, bounded executor for chat request processing, and the async
 * request timeout that goes with it.
 *
 * <p>Deliberately bounded (not Spring MVC's unbounded default async executor)
 * so a burst of concurrent investigations can't spawn unlimited threads --
 * 30 concurrent in-flight chat requests is already generous for this app's
 * realistic load, and a bound here protects the JVM the same way Tomcat's
 * own worker pool bound does for ordinary requests.
 *
 * <p>Timeout set to 6 minutes -- above the longest client-side abort timeout
 * currently in use (5 minutes, for the Cursor model in the frontend), so a
 * genuinely slow-but-working Cursor run isn't cut off by this layer before
 * the browser's own timeout would have applied anyway.
 */
@Configuration
public class AsyncWebConfig implements WebMvcConfigurer {

    @Bean
    public AsyncTaskExecutor chatTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(30);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("chat-async-");
        executor.initialize();
        return executor;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(chatTaskExecutor());
        configurer.setDefaultTimeout(360_000);
    }
}
