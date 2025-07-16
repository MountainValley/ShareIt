package com.valley.ShareIt.support;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorkSpaceDirectory {
    private static final Logger logger = LoggerFactory.getLogger(WorkSpaceDirectory.class);

    private static final String SHARE_IT_WORK_PATH = "SHARE_IT_WORK_PATH";

    private static volatile String workDir;

    public static String getWorkDir() {
        if (workDir == null) {
            synchronized (WorkSpaceDirectory.class) {
                if (workDir == null) {
                    try {
                        initTempWorkDir();
                    } catch (IOException e) {
                        logger.error("init work dir failed", e);
                        throw new RuntimeException("初始化工作目录失败", e);
                    }
                }
            }
        }
        return workDir;
    }

    private static void initWorkDirByEnvConfig() throws FileNotFoundException {
        String baseDir = System.getenv().get(SHARE_IT_WORK_PATH);
        if (baseDir == null){
        throw new RuntimeException("未找到环境变量：SHARE_IT_WORK_PATH。系统将使用该环境变量来指定工作目录");
        }

        File file = new File(baseDir);
        if (!file.exists()) {
        file.mkdirs();
        }

        if(!file.isDirectory() || !file.exists() || !file.canRead() ||
        !file.canWrite()){
        throw new FileNotFoundException("工作目录(" + baseDir + ")不存在或缺少读写权限");
        }

        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
        File workDirFile = new File(file, now.format(dateFormat));
        if (!workDirFile.exists()) {
            if (!workDirFile.mkdirs()) {
                throw new RuntimeException("无法创建工作目录: " + workDirFile.getAbsolutePath());
            }
        }

        logger.info("工作目录已设置为: {}", workDirFile.getAbsolutePath());
        workDir = workDirFile.getAbsolutePath();

    }

    public static void initTempWorkDir() throws IOException {
        Path tempDir = Files.createTempDirectory("shareIt-");
        workDir = tempDir.toAbsolutePath().toString();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Files.deleteIfExists(tempDir);
                logger.info("临时工作目录已删除: {}", tempDir);
            } catch (IOException e) {
                logger.error("删除临时工作目录失败: {}", tempDir, e);
            }
        }));
    }

}