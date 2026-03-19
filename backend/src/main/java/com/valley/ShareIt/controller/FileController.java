package com.valley.ShareIt.controller;

import com.valley.ShareIt.enums.MsgTypeEnum;
import com.valley.ShareIt.support.WorkSpaceDirectory;
import com.valley.ShareIt.utils.FileUtils;
import com.valley.ShareIt.utils.SseClientsManager;
import com.valley.ShareIt.utils.UploadMetadataStore;
import com.valley.ShareIt.vo.DeleteFileRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.converter.ResourceRegionHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/**
 * @author dale
 * @date 2025/9/12
 **/
@RestController
@RequestMapping("/api/file")
@Slf4j
public class FileController {
    private static final int DOWNLOAD_BUFFER_SIZE = 64 * 1024;
    private final ResourceHttpMessageConverter resourceHttpMessageConverter = new ResourceHttpMessageConverter();
    private final ResourceRegionHttpMessageConverter resourceRegionHttpMessageConverter = new ResourceRegionHttpMessageConverter();

    @Value("${disk.free.space.more.than}")
    private long diskFreeSpaceConfig;

    @RequestMapping("list")
    public List<Map<String, Object>> getFiles() {
        // 文件信息列表
        List<Map<String, Object>> fileList = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(Path.of(WorkSpaceDirectory.getWorkDir()))) {
            paths.filter(Files::isRegularFile) // 过滤掉非文件（如目录）
                    .forEach(path -> {
                        try {
                            if (".DS_Store".equals(path.getFileName().toString()) || UploadMetadataStore.isMetadataFile(path)) {
                                return;
                            }
                            // 获取文件属性
                            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);

                            // 将文件信息封装到 Map 中
                            Map<String, Object> fileInfo = new HashMap<>();
                            fileInfo.put("fileName", path.getFileName().toString());
                            fileInfo.put("filePath", path.toAbsolutePath().toString());
                            fileInfo.put("fileSize", FileUtils.formatFileSize(attributes.size()));
                            long uploadedAt = UploadMetadataStore.getUploadedAt(path.getFileName().toString(), attributes);
                            fileInfo.put("creationTime", FileUtils.formatFileTime(attributes.creationTime()));
                            fileInfo.put("lastModifiedTime", FileUtils.formatFileTime(attributes.lastModifiedTime()));
                            fileInfo.put("uploadedAt", FileUtils.formatTimestamp(uploadedAt));
                            fileInfo.put("uploadedAtTimestamp", uploadedAt);
                            fileInfo.put("lastModifiedTimestamp", attributes.lastModifiedTime().toMillis());
                            fileInfo.put("fileUrl", "/api/file/download/" + URLEncoder.encode(path.getFileName().toString(), StandardCharsets.UTF_8));
                            // 添加到列表
                            fileList.add(fileInfo);
                        } catch (IOException e) {
                            System.err.println("无法读取文件属性: " + path);
                        }
                    });

            fileList.sort(Comparator.comparing(
                    file -> ((Number) file.get("uploadedAtTimestamp")).longValue(),
                    Comparator.reverseOrder()
            ));
            return fileList;
        } catch (IOException e) {
            log.error("无法扫描目录: " + WorkSpaceDirectory.getWorkDir(), e);
        }
        return null;
    }

    @GetMapping("workspace")
    public Map<String, String> getWorkspace() {
        Map<String, String> workspaceInfo = new HashMap<>();
        workspaceInfo.put("path", WorkSpaceDirectory.getWorkDir());
        return workspaceInfo;
    }

    @GetMapping("/download/{filename}")
    public ResponseEntity<Void> downloadFile(
            @PathVariable String filename,
            @RequestHeader(value = "Range", required = false) String rangeHeader,
            @RequestHeader(value = "If-Range", required = false) String ifRangeHeader,
            HttpServletResponse response
    ) throws IOException {
        filename = URLDecoder.decode(filename, StandardCharsets.UTF_8);
        File file = new File(WorkSpaceDirectory.getWorkDir(), filename);
        Resource resource = new FileSystemResource(file);
        log.info("receive v2 download request. filename:{} Header Range: {} If-Range: {}", filename, rangeHeader, ifRangeHeader);
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }

        long fileLength = file.length();
        long lastModifiedMillis = file.lastModified();
        String eTag = buildEtag(fileLength, lastModifiedMillis, filename);
        String lastModified = formatHttpDate(lastModifiedMillis);
        String fallbackName = buildAsciiFallbackName(filename);
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replaceAll("\\+", "%20");
        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;

        HttpHeaders headers = buildDownloadHeaders(fallbackName, encodedFilename, eTag, lastModified);

        List<HttpRange> ranges = parseRanges(rangeHeader);
        if (!ranges.isEmpty() && StringUtils.hasText(ifRangeHeader) && !ifRangeMatches(ifRangeHeader, eTag, lastModified)) {
            ranges = Collections.emptyList();
        }

        if (ranges.isEmpty()) {
            headers.setContentType(mediaType);
            headers.setContentLength(fileLength);
            writeHeaders(response, headers, HttpStatus.OK);
            resourceHttpMessageConverter.write(resource, mediaType, new ServletServerHttpResponse(response));
            return null;
        }

        try {
            if (ranges.size() == 1) {
                ResourceRegion region = ranges.get(0).toResourceRegion(resource);
                headers.setContentType(mediaType);
                headers.setContentLength(region.getCount());
                writeHeaders(response, headers, HttpStatus.PARTIAL_CONTENT);
                resourceRegionHttpMessageConverter.write(region, mediaType, new ServletServerHttpResponse(response));
                return null;
            }

            List<ResourceRegion> regions = ranges.stream()
                    .map(range -> range.toResourceRegion(resource))
                    .toList();
            writeHeaders(response, headers, HttpStatus.PARTIAL_CONTENT);
            resourceRegionHttpMessageConverter.write(regions, MediaType.parseMediaType("multipart/byteranges"), new ServletServerHttpResponse(response));
            return null;
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + fileLength)
                    .build();
        }
    }


    @PostMapping("upload")
    public ResponseEntity<String> uploadFile(@RequestParam("files") MultipartFile[] files) {
        try {
            for (MultipartFile file : files) {
                log.info("文件上传开始：" + file.getOriginalFilename());
                if (haveEnoughSpace(file.getSize())) {
                    return ResponseEntity.badRequest().body("文件上传失败，剩余磁盘空间不足：" + FileUtils.formatFileSize(diskFreeSpaceConfig));
                }
                // 保存文件到指定目录
                String filePath = Paths.get(WorkSpaceDirectory.getWorkDir(), file.getOriginalFilename()).toString();
                Files.write(Paths.get(filePath), file.getBytes());
                UploadMetadataStore.recordUpload(file.getOriginalFilename());
                SseClientsManager.sendMsgToAllClients(MsgTypeEnum.FILE_CHANGED.name(), "", null);
            }
            return ResponseEntity.ok("文件上传成功！");
        } catch (IOException e) {
            log.error("文件上传失败", e);
            return ResponseEntity.status(500).body("文件上传失败：" + e.getMessage());
        }
    }

    @PostMapping("/delete")
    public ResponseEntity<String> deleteFile(@RequestBody DeleteFileRequest request) {
        try {
            String fileName = request.getFileName();
            Path filePath = Paths.get(WorkSpaceDirectory.getWorkDir(), fileName);
            if (Files.exists(filePath)) {
                Files.delete(filePath);
                UploadMetadataStore.remove(fileName);
                SseClientsManager.sendMsgToAllClients(MsgTypeEnum.FILE_CHANGED.name(), "", null);
                return ResponseEntity.ok("文件删除成功！");
            } else {
                return ResponseEntity.badRequest().body("文件不存在！");
            }
        } catch (IOException e) {
            return ResponseEntity.status(500).body("文件删除失败：" + e.getMessage());
        }
    }

    private boolean haveEnoughSpace(long size) {
        return (FileUtils.getFreeSpace(WorkSpaceDirectory.getWorkDir()) - size) <= diskFreeSpaceConfig;
    }

    private static HttpHeaders buildDownloadHeaders(String fallbackName, String encodedFilename, String eTag, String lastModified) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.add(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + fallbackName + "\"; filename*=UTF-8''" + encodedFilename);
        headers.add(HttpHeaders.ETAG, eTag);
        headers.add(HttpHeaders.LAST_MODIFIED, lastModified);
        return headers;
    }

    private static List<HttpRange> parseRanges(String rangeHeader) {
        if (!StringUtils.hasText(rangeHeader)) {
            return Collections.emptyList();
        }
        try {
            return HttpRange.parseRanges(rangeHeader);
        } catch (IllegalArgumentException e) {
            return Collections.emptyList();
        }
    }

    private static void writeHeaders(HttpServletResponse response, HttpHeaders headers, HttpStatus status) {
        response.setStatus(status.value());
        headers.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
    }

    private static String buildAsciiFallbackName(String filename) {
        String sanitized = filename.replaceAll("[^\\x20-\\x7E]", "_");
        sanitized = sanitized.replaceAll("[\\\\/:*?\"<>|]", "_");
        sanitized = sanitized.replace(';', '_');
        sanitized = sanitized.trim();
        return sanitized.isEmpty() ? "download" : sanitized;
    }

    private static String buildEtag(long fileLength, long lastModifiedMillis, String filename) {
        return "\"" + fileLength + "-" + lastModifiedMillis + "-" + Integer.toHexString(filename.hashCode()) + "\"";
    }

    private static String formatHttpDate(long epochMillis) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC));
    }

    private static boolean ifRangeMatches(String ifRangeHeader, String eTag, String lastModified) {
        String normalized = ifRangeHeader.trim();
        return normalized.equals(eTag) || normalized.equals(lastModified);
    }
}
