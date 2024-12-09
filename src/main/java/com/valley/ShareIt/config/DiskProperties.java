package com.valley.ShareIt.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * @author dale
 * @since 2024/12/8
 **/
@Configuration
@ConfigurationProperties(prefix = "disk")
public class DiskProperties {
    private Long freeSpaceMoreThan;

    public Long getFreeSpaceMoreThan() {
        return freeSpaceMoreThan;
    }

    public void setFreeSpaceMoreThan(Long freeSpaceMoreThan) {
        this.freeSpaceMoreThan = freeSpaceMoreThan;
    }
}
