package com.valley.ShareIt.controller;

import com.valley.ShareIt.utils.SseClientsManager;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 服务端时间推送服务
 * @author dale
 * @date 2025/9/12
 **/
@RestController
@RequestMapping("/api/sse")
public class SSEController {
    private static final ExecutorService executorService = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(), new ThreadFactory() {
        private final AtomicInteger counter = new AtomicInteger(1);
        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "sse-pool-thread-" + counter.getAndIncrement());
        }
    });

    /**
     * 建立 SSE 连接
     */
    @GetMapping(value = "connect", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connect(@RequestParam String clientId) {
        // 超时时间：30分钟
        SseEmitter emitter = new SseEmitter(0L);
        executorService.execute(() -> {
            try {
                while (true) {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                    Thread.sleep(15000); // 每 15 秒发一个心跳
                }
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        SseClientsManager.addClient(clientId,emitter);

        // 移除失效连接
        emitter.onCompletion(() -> SseClientsManager.removeClient(clientId));
        emitter.onTimeout(() -> SseClientsManager.removeClient(clientId));
        emitter.onError((e) -> SseClientsManager.removeClient(clientId));

        return emitter;
    }
}
