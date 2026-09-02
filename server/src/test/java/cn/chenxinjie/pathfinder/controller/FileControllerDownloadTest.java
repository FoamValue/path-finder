package cn.chenxinjie.pathfinder.controller;

import cn.chenxinjie.pathfinder.service.BizException;
import cn.chenxinjie.pathfinder.service.FileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下载端点 Range 断点下载语义单测（PRD F4 / TSDD 6.4 / TC-DL-003/004/005/006）。
 * 覆盖：200 全量、206 区间、416 不可满足、后缀区间、ZIP 打包、原始文件名 Content-Disposition。
 * 使用 FileService mock 隔离，验证 FileController.download 的 HTTP 语义。
 */
class FileControllerDownloadTest {

    @TempDir
    Path tempDir;

    private final FileService fileService = mock(FileService.class);

    private byte[] writeTextFile(String name, String content) throws Exception {
        Path p = tempDir.resolve(name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private void stubSingle(String token, Path physical, Long fileId, String fileName) {
        FileService.DownloadTarget target =
                new FileService.DownloadTarget("single", fileId, null, fileName);
        when(fileService.consumeToken(token)).thenReturn(target);
        when(fileService.resolveDownloadPath(target)).thenReturn(physical);
    }

    private MockHttpServletResponse download(String token, String range) throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        new FileController(fileService).download(token, range, resp);
        return resp;
    }

    @Test
    void fullDownload_returns200_withOriginalFilenameAndBody() throws Exception {
        byte[] content = writeTextFile("a.txt", "0123456789");
        stubSingle("t-full", tempDir.resolve("a.txt"), 5L, "a.txt");

        MockHttpServletResponse resp = download("t-full", null);

        assertEquals(200, resp.getStatus());
        assertEquals("10", resp.getHeader("Content-Length"));
        assertTrue(resp.getHeader("Content-Disposition").contains("a.txt"),
                "Content-Disposition 保留原始文件名");
        assertTrue(resp.getContentType().contains("text/plain"));
        assertEquals("0123456789", new String(resp.getContentAsByteArray(), StandardCharsets.UTF_8));
        verify(fileService).refreshAfterDownload(5L);
    }

    @Test
    void rangeStartEnd_returns206PartialContent() throws Exception {
        writeTextFile("a.txt", "0123456789");
        stubSingle("t1", tempDir.resolve("a.txt"), 5L, "a.txt");

        MockHttpServletResponse resp = download("t1", "bytes=0-4");

        assertEquals(206, resp.getStatus());
        assertEquals("bytes 0-4/10", resp.getHeader("Content-Range"));
        assertEquals("01234", new String(resp.getContentAsByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    void rangeMid_returns206WithExactSlice() throws Exception {
        writeTextFile("a.txt", "0123456789");
        stubSingle("t2", tempDir.resolve("a.txt"), 5L, "a.txt");

        MockHttpServletResponse resp = download("t2", "bytes=5-9");

        assertEquals(206, resp.getStatus());
        assertEquals("56789", new String(resp.getContentAsByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    void rangeBeyondSize_returns416() throws Exception {
        writeTextFile("a.txt", "0123456789");
        stubSingle("t3", tempDir.resolve("a.txt"), 5L, "a.txt");

        MockHttpServletResponse resp = download("t3", "bytes=99-");

        assertEquals(416, resp.getStatus());
        assertEquals("bytes */10", resp.getHeader("Content-Range"));
        assertEquals(0, resp.getContentAsByteArray().length);
        verify(fileService, never()).refreshAfterDownload(anyLong());
    }

    @Test
    void suffixRange_returnsLastBytes() throws Exception {
        writeTextFile("a.txt", "0123456789");
        stubSingle("t4", tempDir.resolve("a.txt"), 5L, "a.txt");

        MockHttpServletResponse resp = download("t4", "bytes=-3");

        assertEquals(206, resp.getStatus());
        assertEquals("789", new String(resp.getContentAsByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    void physicalFileMissing_throws404() throws Exception {
        writeTextFile("ghost.txt", "x");
        FileService.DownloadTarget target =
                new FileService.DownloadTarget("single", 7L, null, "ghost.txt");
        when(fileService.consumeToken("t-miss")).thenReturn(target);
        when(fileService.resolveDownloadPath(target)).thenReturn(tempDir.resolve("not-exist.txt"));

        BizException e = assertThrows(BizException.class, () -> download("t-miss", null));
        assertEquals(404, e.getStatus());
    }

    @Test
    void zipDownload_servesTmpZip_withoutSingleFileRefresh() throws Exception {
        Path zip = tempDir.resolve("pkg.zip");
        byte[] zdata = "PK-content".getBytes(StandardCharsets.UTF_8);
        Files.write(zip, zdata);
        FileService.DownloadTarget target =
                new FileService.DownloadTarget("zip", null, zip.toString(), "pkg.zip");
        when(fileService.consumeToken("t-zip")).thenReturn(target);
        when(fileService.resolveDownloadPath(target)).thenReturn(zip);

        MockHttpServletResponse resp = download("t-zip", null);

        assertEquals(200, resp.getStatus());
        assertEquals("application/zip", resp.getContentType());
        assertEquals("PK-content", new String(resp.getContentAsByteArray(), StandardCharsets.UTF_8));
        verify(fileService, never()).refreshAfterDownload(anyLong());
    }
}
