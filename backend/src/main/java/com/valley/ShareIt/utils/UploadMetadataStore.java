package com.valley.ShareIt.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.valley.ShareIt.support.WorkSpaceDirectory;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public final class UploadMetadataStore {
    private static final String METADATA_FILE_NAME = ".shareit-upload-metadata.json";
    private static final Object LOCK = new Object();

    private UploadMetadataStore() {
    }

    public static long getUploadedAt(String fileName, BasicFileAttributes attributes) {
        synchronized (LOCK) {
            Map<String, Long> metadata = loadMetadata();
            Long uploadedAt = metadata.get(fileName);
            if (uploadedAt != null) {
                return uploadedAt;
            }

            long fallbackTimestamp = attributes.creationTime() != null
                    ? attributes.creationTime().toMillis()
                    : attributes.lastModifiedTime().toMillis();
            metadata.put(fileName, fallbackTimestamp);
            saveMetadata(metadata);
            return fallbackTimestamp;
        }
    }

    public static void recordUpload(String fileName) {
        synchronized (LOCK) {
            Map<String, Long> metadata = loadMetadata();
            metadata.put(fileName, Instant.now().toEpochMilli());
            saveMetadata(metadata);
        }
    }

    public static void remove(String fileName) {
        synchronized (LOCK) {
            Map<String, Long> metadata = loadMetadata();
            if (metadata.remove(fileName) != null) {
                saveMetadata(metadata);
            }
        }
    }

    private static Map<String, Long> loadMetadata() {
        Path metadataFile = getMetadataFile();
        if (!Files.exists(metadataFile)) {
            return new HashMap<>();
        }

        try {
            String content = Files.readString(metadataFile, StandardCharsets.UTF_8);
            if (content == null || content.isBlank()) {
                return new HashMap<>();
            }
            Map<String, Long> metadata = JSON.parseObject(content, new TypeReference<Map<String, Long>>() {
            });
            return metadata != null ? new HashMap<>(metadata) : new HashMap<>();
        } catch (IOException e) {
            log.error("读取上传元数据失败: {}", metadataFile, e);
            return new HashMap<>();
        }
    }

    private static void saveMetadata(Map<String, Long> metadata) {
        Path metadataFile = getMetadataFile();
        try {
            Files.writeString(
                    metadataFile,
                    JSON.toJSONString(metadata),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (IOException e) {
            log.error("保存上传元数据失败: {}", metadataFile, e);
        }
    }

    private static Path getMetadataFile() {
        return Path.of(WorkSpaceDirectory.getWorkDir(), METADATA_FILE_NAME);
    }

    public static boolean isMetadataFile(Path path) {
        return path != null && METADATA_FILE_NAME.equals(path.getFileName().toString());
    }
}
