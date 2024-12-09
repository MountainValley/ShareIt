package com.valley.ShareIt.controller;

import com.valley.ShareIt.utils.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Stream;

/**
 * @author dale
 * @since 2024/12/7
 **/
@Controller
@RequestMapping("file")
public class MainController {
    private static final Log logger = LogFactory.getLog(MainController.class);
    private static final String BASE_DIR = System.getenv().get("SHARE_IT_FROM");

    @Value("${disk.free.space.more.than}")
    private long diskFreeSpaceConfig;

    @GetMapping("home")
    public String homePage() {
        return "main.html";
    }

    @RequestMapping("list")
    @ResponseBody
    public List<Map<String, Object>> getFiles() {
        // 文件信息列表
        List<Map<String, Object>> fileList = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(Path.of(BASE_DIR))) {
            paths.filter(Files::isRegularFile) // 过滤掉非文件（如目录）
                    .forEach(path -> {
                        try {
                            if (".DS_Store".equals(path.getFileName().toString())){
                                return;
                            }
                            // 获取文件属性
                            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);

                            // 将文件信息封装到 Map 中
                            Map<String, Object> fileInfo = new HashMap<>();
                            fileInfo.put("fileName", path.getFileName().toString());
                            fileInfo.put("filePath", path.toAbsolutePath().toString());
                            fileInfo.put("fileSize", FileUtils.formatFileSize(attributes.size()));
                            fileInfo.put("creationTime", FileUtils.formatFileTime(attributes.creationTime()));
                            fileInfo.put("lastModifiedTime", FileUtils.formatFileTime(attributes.lastModifiedTime()));
                            fileInfo.put("fileUrl", "/file/download/" + URLEncoder.encode(path.getFileName().toString(), StandardCharsets.UTF_8));

                            // 添加到列表
                            fileList.add(fileInfo);
                        } catch (IOException e) {
                            System.err.println("无法读取文件属性: " + path);
                        }
                    });

            return fileList;
        } catch (IOException e) {
            logger.error("无法扫描目录: " + BASE_DIR, e);
        }
        return null;
    }

    @GetMapping("/download/{filename}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String filename) throws IOException {
        filename = URLDecoder.decode(filename, StandardCharsets.UTF_8);
        File file = new File(BASE_DIR, filename);

        if (!file.exists()) {
            // 如果文件不存在，返回 404 错误
            return ResponseEntity.notFound().build();
        }

        // 创建文件资源
        Resource resource = new FileSystemResource(file);

        // 设置响应头，告诉浏览器这是一个下载文件
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        headers.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE);

        return ResponseEntity.ok()
                .headers(headers)
                .contentLength(file.length())
                .body(resource);
    }


    @PostMapping("upload")
    public ResponseEntity<String> uploadFile(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("文件不能为空！");
        } else if (haveEnoughSpace(file.getSize())) {
            return ResponseEntity.badRequest().body("文件上传失败，剩余磁盘空间不足：" + FileUtils.formatFileSize(diskFreeSpaceConfig));
        }

        try {
            // 保存文件到指定目录
            String filePath = Paths.get(BASE_DIR, file.getOriginalFilename()).toString();
            Files.write(Paths.get(filePath), file.getBytes());
            return ResponseEntity.ok("文件上传成功！");
        } catch (IOException e) {
            logger.error("文件上传失败", e);
            return ResponseEntity.status(500).body("文件上传失败：" + e.getMessage());
        }
    }

    private boolean haveEnoughSpace(long size) {
        return (FileUtils.getFreeSpace(BASE_DIR) - size) <= diskFreeSpaceConfig;
    }
}
