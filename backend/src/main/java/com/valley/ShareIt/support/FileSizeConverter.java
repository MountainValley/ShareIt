package com.valley.ShareIt.support;

import com.valley.ShareIt.utils.FileUtils;
import org.springframework.core.convert.converter.Converter;

/**
 * @author dale
 * @since 2024/12/8
 **/
public class FileSizeConverter implements Converter<String, Long> {
    @Override
    public Long convert(String source) {
        return FileUtils.convertToBytes(source);
    }
}
