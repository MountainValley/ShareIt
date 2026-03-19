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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.ACCEPT_RANGES;
import static org.springframework.http.HttpHeaders.CONTENT_LENGTH;
import static org.springframework.http.HttpHeaders.CONTENT_RANGE;
import static org.springframework.http.HttpHeaders.ETAG;
import static org.springframework.http.HttpHeaders.LAST_MODIFIED;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.http.HttpStatus.PARTIAL_CONTENT;
import static org.springframework.http.HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
