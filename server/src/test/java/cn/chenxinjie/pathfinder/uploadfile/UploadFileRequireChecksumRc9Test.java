package cn.chenxinjie.pathfinder.uploadfile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.chenxinjie.uploadfile.core.exception.ChecksumMismatchException;
import cn.chenxinjie.uploadfile.core.exception.UploadValidationException;
import cn.chenxinjie.uploadfile.core.model.ChunkUploadRequest;
import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import cn.chenxinjie.uploadfile.core.store.MemoryTaskStore;
import cn.chenxinjie.uploadfile.core.storage.LocalFileChunkStorage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证组件 rc.9 安全收口之一 M1：配合 {@code verify-checksum + require-checksum} 时，缺失
 * {@code chunkMd5} 的分块必须被拒绝并删除，而不能像 rc.9 之前那样静默跳过校验。
 *
 * <p>直接驱动核心 {@link ResumableUploadService}（rc.9 生产类，无需 Spring 上下文 / Redis / DB），
 * 因此单元级即可稳定复现该行为。</p>
 */
class UploadFileRequireChecksumRc9Test {

    @TempDir
    Path tempDir;

    /** 构造带校验的服务：以 4 参构造启用 verifyChecksum=true，再按需开关 requireChecksum。 */
    private ResumableUploadService service(LocalFileChunkStorage chunks, boolean requireChecksum) {
        ResumableUploadService svc = new ResumableUploadService(
                new MemoryTaskStore(), chunks, tempDir.resolve("merged").toFile(), true);
        svc.setRequireChecksum(requireChecksum);
        return svc;
    }

    private LocalFileChunkStorage chunks() {
        return new LocalFileChunkStorage(tempDir.resolve("chunks"));
    }

    private ChunkUploadRequest request(String identifier, String md5) {
        ChunkUploadRequest req = new ChunkUploadRequest();
        req.setIdentifier(identifier);
        req.setFileName("payload.bin");
        req.setFileSize(4);
        req.setChunkSize(4);
        req.setChunkTotal(1);
        req.setChunkIndex(0);
        req.setChunkMd5(md5);
        return req;
    }

    private static byte[] payload() {
        return new byte[] {1, 2, 3, 4};
    }

    @Test
    void missingChunkMd5_isRejectedAndChunkDeleted_whenRequireChecksumOn() throws IOException {
        LocalFileChunkStorage chunks = chunks();
        ResumableUploadService svc = service(chunks, true);
        byte[] data = payload();

        UploadValidationException ex = assertThrows(UploadValidationException.class,
                () -> svc.uploadChunk(request("id-missing", null), new ByteArrayInputStream(data)));

        assertThat(ex.getMessage()).contains("missing the required checksum");
        // 分块已落盘后因缺校验被删除，不得残留半个分片
        assertThat(chunks.getChunkFile("id-missing", 0)).doesNotExist();
    }

    @Test
    void missingChunkMd5_isAcceptedAsLegacy_whenRequireChecksumOff() throws IOException {
        // 对照：rc.9 默认 require-checksum=false，缺失 chunkMd5 仍走旧行为（向后兼容）。
        LocalFileChunkStorage chunks = chunks();
        ResumableUploadService svc = service(chunks, false);
        byte[] data = payload();

        svc.uploadChunk(request("id-legacy", null), new ByteArrayInputStream(data));

        assertThat(chunks.getChunkFile("id-legacy", 0))
                .exists()
                .hasBinaryContent(data);
    }

    @Test
    void mismatchedChunkMd5_isRejectedAndChunkDeleted_whenRequireChecksumOn() throws Exception {
        LocalFileChunkStorage chunks = chunks();
        ResumableUploadService svc = service(chunks, true);
        byte[] data = payload();
        String wrongMd5 = HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                .digest(new byte[] {9, 9, 9, 9}));

        ChecksumMismatchException ex = assertThrows(ChecksumMismatchException.class,
                () -> svc.uploadChunk(request("id-wrong", wrongMd5), new ByteArrayInputStream(data)));

        assertThat(ex.getMessage()).contains("MD5 mismatch");
        assertThat(chunks.getChunkFile("id-wrong", 0)).doesNotExist();
    }

    @Test
    void matchingChunkMd5_isAccepted_whenRequireChecksumOn() throws IOException, Exception {
        // 正向：提供正确 chunkMd5 的分块应正常落盘记录。
        LocalFileChunkStorage chunks = chunks();
        ResumableUploadService svc = service(chunks, true);
        byte[] data = payload();
        String md5 = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));

        svc.uploadChunk(request("id-correct", md5), new ByteArrayInputStream(data));

        assertThat(chunks.getChunkFile("id-correct", 0))
                .exists()
                .hasBinaryContent(data);
    }
}