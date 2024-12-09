package com.valley.ShareIt.support;

import com.valley.ShareIt.utils.NetWorkUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.web.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.SocketException;

/**
 * @author dale
 * @since 2024/12/7
 **/
@Component
public class ContextListener implements ApplicationListener {
    private static final Log logger = LogFactory.getLog(ContextListener.class);
    private static final String PROTOCOL = "http://";
    private static final String HOME_PAGE = "/file/home";

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof ServletWebServerInitializedEvent servletEvent){
            String url;
            try {
                url = PROTOCOL+ NetWorkUtils.getLocalIpAddress() + ":" + servletEvent.getApplicationContext().getWebServer().getPort() +HOME_PAGE;
            } catch (SocketException e) {
                throw new RuntimeException(e);
            }
            logger.info("service address: " + url);
            try {
                String os = System.getProperty("os.name").toLowerCase();
                if (os.startsWith("mac")) {
                    Runtime.getRuntime().exec("open " + url);
                }else if (os.startsWith("windows")){
                    Runtime.getRuntime().exec("start " + url);
                }
            } catch (IOException e) {
                logger.error("open link "+url+" failed",e);
            }
        }
    }
}
