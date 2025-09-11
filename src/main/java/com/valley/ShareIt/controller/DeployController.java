package com.valley.ShareIt.controller;

import com.valley.ShareIt.enums.MsgTypeEnum;
import com.valley.ShareIt.support.WorkSpaceDirectory;
import com.valley.ShareIt.utils.SseClientsManager;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;

/**
 * @author dale
 * @since 2024/12/7
 **/
@Controller
@RequestMapping("deploy")
public class DeployController {
    private static final Log logger = LogFactory.getLog(DeployController.class);
    private static final String DEPLOY_WORK_PATH = "D:"+File.separator+"DockerWorkSpace"+File.separator+"meta-server";

    @PostMapping("upload")
    public ResponseEntity<String> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("文件不能为空！");
        }
        // 判断是否jar文件
        if (!file.getOriginalFilename().endsWith(".jar")) {
            return ResponseEntity.badRequest().body("请上传jar文件！");
        }

        try {
            // 保存文件到docker部署目录
            String filePath = Paths.get(DEPLOY_WORK_PATH, file.getOriginalFilename()).toString();
            Files.write(Paths.get(filePath), file.getBytes());
            // 复制文件到文件中转站以关注文件更新情况
            String fileTransPath = Paths.get(WorkSpaceDirectory.getWorkDir(), file.getOriginalFilename()).toString();
            Files.copy(Paths.get(filePath), Paths.get(fileTransPath), StandardCopyOption.REPLACE_EXISTING);

            SseClientsManager.sendMsgToAllClients(MsgTypeEnum.FILE_CHANGED.name(), "",null);

            // 执行docker restart命令
            String[] array = file.getOriginalFilename().split("-");
            if (array.length < 2) {
                return ResponseEntity.badRequest().body("文件名格式错误，无法确定 Docker 容器名！");
            }
            String dockerName = array[0]+"-"+array[1];
//            Runtime.getRuntime().exec(new String[]{"cmd", "/c", "docker", "restart", dockerName});

            ProcessBuilder pb = new ProcessBuilder("docker", "restart", dockerName);
            Process process = pb.start();
            int exitCode = 0;
            try {
                exitCode = process.waitFor();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            if (exitCode != 0) {
                return ResponseEntity.status(500).body("文件上传成功，但重启容器失败！");
            }

            return ResponseEntity.ok("部署成功！");
        } catch (IOException e) {
            logger.error("文件上传失败", e);
            return ResponseEntity.status(500).body("文件上传失败：" + e.getMessage());
        }
    }


}
