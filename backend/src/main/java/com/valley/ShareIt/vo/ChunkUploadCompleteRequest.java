package com.valley.ShareIt.vo;

import lombok.Data;

@Data
public class ChunkUploadCompleteRequest {
    private String uploadId;
    private String fileName;
    private int totalChunks;
    private long totalSize;
}
