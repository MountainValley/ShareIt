package com.valley.ShareIt;

import com.valley.ShareIt.config.DiskProperties;
import com.valley.ShareIt.support.FileSizeConverter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.format.support.DefaultFormattingConversionService;

import java.io.File;

@SpringBootApplication
@EnableConfigurationProperties(DiskProperties.class)
public class ShareItApplication {
	private static final Log logger = LogFactory.getLog(ShareItApplication.class);
	private static final String SHARE_IT_FROM_ENV = "SHARE_IT_FROM";
	public static void main(String[] args) {
		String baseDir = System.getenv().get(SHARE_IT_FROM_ENV);
		if (baseDir == null){
			logger.error("未找到环境变量：SHARE_IT_FROM。");
			return;
		}
		File file = new File(baseDir);
		if(!file.isDirectory() || !file.exists() || !file.canRead() || !file.canWrite()){
			logger.error("工作目录不存在或缺少读写权限");
			return;
		}

		SpringApplication.run(ShareItApplication.class, args);
    }

	@Bean
	public DefaultFormattingConversionService conversionService() {
		DefaultFormattingConversionService service = new DefaultFormattingConversionService();
		service.addConverter(new FileSizeConverter());
		return service;
	}
}
