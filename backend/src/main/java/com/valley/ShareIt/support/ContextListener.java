package com.valley.ShareIt.support;

import com.valley.ShareIt.utils.NetWorkUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.net.SocketException;

/**
 * @author dale
 * @since 2024/12/7
 **/
@Component
public class ContextListener implements ApplicationListener {
    private static final Log logger = LogFactory.getLog(ContextListener.class);
    private static final String PROTOCOL = "http://";
    private static final String DEFAULT_HOME_PAGE = "/";

    @Value("${app.browser.open.enabled:true}")
    private boolean browserOpenEnabled;

    @Value("${app.browser.open.target-port:0}")
    private int browserTargetPort;

    @Value("${app.browser.open.path:/}")
    private String browserOpenPath;

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (!browserOpenEnabled) {
            return;
        }
        if (event instanceof ServletWebServerInitializedEvent servletEvent){
            String url;
            try {
                String host = NetWorkUtils.getLocalIpAddress();
                int port = browserTargetPort > 0 ? browserTargetPort : servletEvent.getApplicationContext().getWebServer().getPort();
                String path = browserOpenPath == null || browserOpenPath.isBlank() ? DEFAULT_HOME_PAGE : browserOpenPath;
                url = PROTOCOL + host + ":" + port + path;
            } catch (SocketException e) {
                throw new RuntimeException(e);
            }
            logger.info("service address: " + url);
            try {
                String os = System.getProperty("os.name").toLowerCase();
                if (os.startsWith("mac")) {
                    Runtime.getRuntime().exec("open " + url);
                }else if (os.startsWith("windows")){
                    Runtime.getRuntime().exec(new String[]{"cmd", "/c", "start", "", url});
                }
            } catch (Exception e) {
                logger.error("open link "+url+" failed",e);
            }
        }
    }
}
