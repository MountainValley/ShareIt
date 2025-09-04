package com.valley.ShareIt.controller;

import com.valley.ShareIt.enums.MsgTypeEnum;
import com.valley.ShareIt.support.WorkSpaceDirectory;
import com.valley.ShareIt.utils.FileUtils;
import com.valley.ShareIt.utils.SseClientsManager;
import com.valley.ShareIt.utils.TextContainer;
import com.valley.ShareIt.vo.DeleteFileRequest;
import com.valley.ShareIt.vo.SubmitTextRequest;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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

        try (Stream<Path> paths = Files.walk(Path.of(WorkSpaceDirectory.getWorkDir()))) {
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
            logger.error("无法扫描目录: " + WorkSpaceDirectory.getWorkDir(), e);
        }
        return null;
    }

    @GetMapping("/download/{filename}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String filename) throws IOException {
        filename = URLDecoder.decode(filename, StandardCharsets.UTF_8);
        File file = new File(WorkSpaceDirectory.getWorkDir(), filename);

        if (!file.exists()) {
            // 如果文件不存在，返回 404 错误
            return ResponseEntity.notFound().build();
        }

        // 创建文件资源
        Resource resource = new FileSystemResource(file);

        // 兼容 ASCII 的 fallback 名（比如转为拼音、数字、uuid等简单英文名）
        String fallbackName = "download.pdf";

        // 对中文名做 UTF-8 URL 编码，并替换 + 为 %20
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replaceAll("\\+", "%20");

        // 设置响应头，告诉浏览器这是一个下载文件
        HttpHeaders headers = new HttpHeaders();
        // headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        headers.add(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + fallbackName + "\"; filename*=UTF-8''" + encodedFilename);
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
            String filePath = Paths.get(WorkSpaceDirectory.getWorkDir(), file.getOriginalFilename()).toString();
            Files.write(Paths.get(filePath), file.getBytes());
            SseClientsManager.sendMsgToAllClients(MsgTypeEnum.FILE_CHANGED.name(), "",null);
            return ResponseEntity.ok("文件上传成功！");
        } catch (IOException e) {
            logger.error("文件上传失败", e);
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
                SseClientsManager.sendMsgToAllClients(MsgTypeEnum.FILE_CHANGED.name(), "",  null);
                return ResponseEntity.ok("文件删除成功！");
            } else {
                return ResponseEntity.badRequest().body("文件不存在！");
            }
        }catch (IOException e){
            return ResponseEntity.status(500).body("文件删除失败：" + e.getMessage());
        }
    }


    @PostMapping("text")
    public ResponseEntity<String> submitText(@RequestBody SubmitTextRequest request) {
        TextContainer.setText(request.getText());
        SseClientsManager.sendMsgToAllClients(MsgTypeEnum.TEXT_CHANGED.name(), request.getText(), request.getClientId());
        return ResponseEntity.ok("提交成功！");
    }

    @GetMapping("text")
    public ResponseEntity<String> getText() {
        return ResponseEntity.ok(TextContainer.getText());
    }

    /**
     * 建立 SSE 连接
     */
    @GetMapping(value = "/sse/connect", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connect(@RequestParam String clientId) {
        // 超时时间：30分钟
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        SseClientsManager.addClient(clientId,emitter);

        // 移除失效连接
        emitter.onCompletion(() -> SseClientsManager.removeClient(clientId));
        emitter.onTimeout(() -> SseClientsManager.removeClient(clientId));
        emitter.onError((e) -> SseClientsManager.removeClient(clientId));

        return emitter;
    }
    private boolean haveEnoughSpace(long size) {
        return (FileUtils.getFreeSpace(WorkSpaceDirectory.getWorkDir()) - size) <= diskFreeSpaceConfig;
    }
}
