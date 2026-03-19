package com.valley.ShareIt.controller;

import com.valley.ShareIt.utils.SseClientsManager;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 服务端时间推送服务
 * @author dale
 * @date 2025/9/12
 **/
@RestController
@RequestMapping("/api/sse")
public class SSEController {
    private static final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1, new ThreadFactory() {
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
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean active = new AtomicBoolean(true);
        ScheduledFuture<?> heartbeatFuture = executorService.scheduleAtFixedRate(() -> {
            if (!active.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (IOException | IllegalStateException e) {
                active.set(false);
                SseClientsManager.removeClient(clientId, emitter);
            }
        }, 0, 15, TimeUnit.SECONDS);

        Runnable cleanup = () -> {
            active.set(false);
            heartbeatFuture.cancel(true);
            SseClientsManager.removeClient(clientId, emitter);
        };

        SseClientsManager.addClient(clientId, emitter);

        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError((e) -> cleanup.run());

        return emitter;
    }
}
