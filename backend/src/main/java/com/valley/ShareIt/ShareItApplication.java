package com.valley.ShareIt;

import com.valley.ShareIt.config.DiskProperties;
import com.valley.ShareIt.support.FileSizeConverter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.format.support.DefaultFormattingConversionService;

@SpringBootApplication
@EnableConfigurationProperties(DiskProperties.class)
public class ShareItApplication {
	public static void main(String[] args) {
		SpringApplication.run(ShareItApplication.class, args);
    }

	@Bean
	public DefaultFormattingConversionService conversionService() {
		DefaultFormattingConversionService service = new DefaultFormattingConversionService();
		service.addConverter(new FileSizeConverter());
		return service;
	}
}
