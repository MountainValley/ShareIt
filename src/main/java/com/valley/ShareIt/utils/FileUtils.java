package com.valley.ShareIt.utils;

import java.io.File;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author dale
 * @since 2024/12/8
 **/
public class FileUtils {
    /**
     * 将字节数转换为易读的文件大小字符串表示
     *
     * @param size 字节数
     * @return 易读的文件大小字符串
     */
    public static String formatFileSize(long size) {
        if (size < 0) {
            return "Invalid size";
        }

        final String[] units = {"B", "KB", "MB", "GB", "TB", "PB", "EB"};
        int unitIndex = 0;
        double readableSize = size;

        while (readableSize >= 1024 && unitIndex < units.length - 1) {
            readableSize /= 1024.0;
            unitIndex++;
        }

        return String.format("%.2f %s", readableSize, units[unitIndex]);
    }

    public static long convertToBytes(String fileSize) {
        // 正则表达式匹配文件大小（数字 + 单位）
        Pattern pattern = Pattern.compile("(?i)^(\\d+(\\.\\d+)?)\\s*(B|KB|MB|GB|TB|PB)$");
        Matcher matcher = pattern.matcher(fileSize.trim());

        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid file size format: " + fileSize);
        }

        // 获取数字部分
        double size = Double.parseDouble(matcher.group(1));
        // 获取单位部分（转换为大写处理）
        String unit = matcher.group(3).toUpperCase();

        // 单位映射到字节数
        switch (unit) {
            case "B":  return (long) size;
            case "KB": return (long) (size * 1024);
            case "MB": return (long) (size * 1024 * 1024);
            case "GB": return (long) (size * 1024 * 1024 * 1024);
            case "TB": return (long) (size * 1024 * 1024 * 1024 * 1024);
            case "PB": return (long) (size * 1024 * 1024 * 1024 * 1024 * 1024);
            default:   throw new IllegalArgumentException("Unsupported unit: " + unit);
        }
    }

    /**
     * 将 FileTime 转换为字符串，使用当前时区
     *
     * @param fileTime 文件时间
     * @return 格式化的时间字符串
     */
    public static String formatFileTime(FileTime fileTime) {
        if (fileTime == null) {
            return "Invalid time";
        }

        // 获取系统默认时区
        ZoneId zoneId = ZoneId.systemDefault();

        // 定义日期时间格式化器
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(zoneId);

        // 转换为字符串
        Instant instant = fileTime.toInstant();
        return formatter.format(instant);
    }

    /**
     * 获取剩余空间大小
     * @param path
     * @return
     */
    public static long getFreeSpace(String path){
        File disk = new File(path);
        return disk.getFreeSpace();
    }

}
