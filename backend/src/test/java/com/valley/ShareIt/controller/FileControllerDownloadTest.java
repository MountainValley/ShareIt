package com.valley.ShareIt.controller;

import com.valley.ShareIt.support.WorkSpaceDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.HttpHeaders.ACCEPT_RANGES;
import static org.springframework.http.HttpHeaders.CONTENT_LENGTH;
import static org.springframework.http.HttpHeaders.CONTENT_RANGE;
import static org.springframework.http.HttpHeaders.ETAG;
import static org.springframework.http.HttpHeaders.LAST_MODIFIED;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.http.HttpStatus.PARTIAL_CONTENT;
import static org.springframework.http.HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.mock.web.MockMultipartFile;

@SpringBootTest
@AutoConfigureMockMvc
class FileControllerDownloadTest {

    @Autowired
    private MockMvc mockMvc;

    @TempDir
    Path tempDir;

    private String originalWorkDir;

    @BeforeEach
    void setUp() throws Exception {
        originalWorkDir = getWorkDirField();
        setWorkDirField(tempDir.toString());
        Files.writeString(tempDir.resolve("sample.txt"), "0123456789", StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() throws Exception {
        setWorkDirField(originalWorkDir);
        Path metadataPath = tempDir.resolve(".shareit-upload-metadata.json");
        if (Files.exists(metadataPath)) {
            Files.delete(metadataPath);
        }
        Path uploadCache = tempDir.resolve(".shareit-upload-cache");
        if (Files.exists(uploadCache)) {
            try (var walk = Files.walk(uploadCache)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    @Test
    void shouldDownloadWholeFileWhenNoRangeHeader() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/file/download/sample.txt"))
                .andExpect(status().is(OK.value()))
                .andReturn();

        assertThat(result.getResponse().getHeader(ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(result.getResponse().getHeader(CONTENT_LENGTH)).isEqualTo("10");
        assertThat(result.getResponse().getHeader(ETAG)).isNotBlank();
        assertThat(result.getResponse().getHeader(LAST_MODIFIED)).isNotBlank();
        assertThat(result.getResponse().getContentAsString()).isEqualTo("0123456789");
    }

    @Test
    void shouldResumeDownloadWhenRangeHeaderProvided() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/file/download/sample.txt").header("Range", "bytes=4-"))
                .andExpect(status().is(PARTIAL_CONTENT.value()))
                .andReturn();

        assertThat(result.getResponse().getHeader(CONTENT_RANGE)).isEqualTo("bytes 4-9/10");
        assertThat(result.getResponse().getHeader(CONTENT_LENGTH)).isEqualTo("6");
        assertThat(result.getResponse().getContentAsString()).isEqualTo("456789");
    }

    @Test
    void shouldReturn416WhenRangeStartExceedsFileLength() throws Exception {
        mockMvc.perform(get("/api/file/download/sample.txt").header("Range", "bytes=100-"))
                .andExpect(status().is(REQUESTED_RANGE_NOT_SATISFIABLE.value()))
                .andExpect(result -> assertThat(result.getResponse().getHeader(CONTENT_RANGE)).isEqualTo("bytes */10"));
    }

    @Test
    void shouldIgnoreInvalidRangeHeader() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/file/download/sample.txt").header("Range", "items=1-2"))
                .andExpect(status().is(OK.value()))
                .andReturn();

        assertThat(result.getResponse().getHeader(CONTENT_RANGE)).isNull();
        assertThat(result.getResponse().getContentAsString()).isEqualTo("0123456789");
    }

    @Test
    void shouldResumeDownloadWhenIfRangeMatchesEtag() throws Exception {
        MvcResult initialResult = mockMvc.perform(get("/api/file/download/sample.txt"))
                .andExpect(status().isOk())
                .andReturn();

        String etag = initialResult.getResponse().getHeader(ETAG);

        MvcResult result = mockMvc.perform(get("/api/file/download/sample.txt")
                        .header("Range", "bytes=3-")
                        .header("If-Range", etag))
                .andExpect(status().is(PARTIAL_CONTENT.value()))
                .andReturn();

        assertThat(result.getResponse().getHeader(CONTENT_RANGE)).isEqualTo("bytes 3-9/10");
        assertThat(result.getResponse().getContentAsString()).isEqualTo("3456789");
    }

    @Test
    void shouldReturnWholeFileWhenIfRangeDoesNotMatch() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/file/download/sample.txt")
                        .header("Range", "bytes=3-")
                        .header("If-Range", "\"stale-etag\""))
                .andExpect(status().is(OK.value()))
                .andReturn();

        assertThat(result.getResponse().getHeader(CONTENT_RANGE)).isNull();
        assertThat(result.getResponse().getHeader(CONTENT_LENGTH)).isEqualTo("10");
        assertThat(result.getResponse().getContentAsString()).isEqualTo("0123456789");
    }

    @Test
    void shouldAssembleFileWhenChunkUploadCompletes() throws Exception {
        MockMultipartFile chunk0 = new MockMultipartFile("chunk", "chunk-0", "application/octet-stream", "hello ".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile chunk1 = new MockMultipartFile("chunk", "chunk-1", "application/octet-stream", "world".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/file/upload/chunk")
                        .file(chunk0)
                        .param("uploadId", "upload-1")
                        .param("fileName", "chunked.txt")
                        .param("chunkIndex", "0")
                        .param("totalChunks", "2")
                        .param("totalSize", "11"))
                .andExpect(status().isOk());

        mockMvc.perform(multipart("/api/file/upload/chunk")
                        .file(chunk1)
                        .param("uploadId", "upload-1")
                        .param("fileName", "chunked.txt")
                        .param("chunkIndex", "1")
                        .param("totalChunks", "2")
                        .param("totalSize", "11"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/file/upload/complete")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "uploadId": "upload-1",
                                  "fileName": "chunked.txt",
                                  "totalChunks": 2,
                                  "totalSize": 11
                                }
                                """))
                .andExpect(status().isOk());

        assertThat(Files.readString(tempDir.resolve("chunked.txt"), StandardCharsets.UTF_8)).isEqualTo("hello world");
        assertThat(Files.exists(tempDir.resolve(".shareit-upload-cache").resolve("upload-1"))).isFalse();
    }

    private static String getWorkDirField() throws Exception {
        Field field = WorkSpaceDirectory.class.getDeclaredField("workDir");
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private static void setWorkDirField(String workDir) throws Exception {
        Field field = WorkSpaceDirectory.class.getDeclaredField("workDir");
        field.setAccessible(true);
        field.set(null, workDir);
    }
}
