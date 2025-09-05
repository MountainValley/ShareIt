package com.valley.ShareIt.utils;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @author dale
 * @since 2025/9/4
 **/
public class SseClientsManager {
    // 保存所有客户端连接
    private static final Map<String,SseEmitter> clients = new ConcurrentHashMap<>();


    public static void addClient(String clientId, SseEmitter client) {
        clients.put(clientId,client);
    }

    public static void removeClient(String clientId) {
        clients.remove(clientId);
    }

    public static void sendMsgToAllClients(String msgType,String msgContent, String excludeClientId) {
        Map<String,String> map = new HashMap<>();
        map.put("type",msgType);
        map.put("content",msgContent);
        String msg = JSONObject.toJSONString(map);
        for (Map.Entry<String, SseEmitter> entry : clients.entrySet()) {
            try {
                if (!entry.getKey().equals(excludeClientId)) {
                    entry.getValue().send(msg);
                }
            } catch (Exception e) {
                // 移除掉异常的客户端
                removeClient(entry.getKey());
            }
        }
    }
}
