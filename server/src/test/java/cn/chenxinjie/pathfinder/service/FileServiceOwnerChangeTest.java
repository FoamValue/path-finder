package cn.chenxinjie.pathfinder.service;

import cn.chenxinjie.pathfinder.config.PathProperties;
import cn.chenxinjie.pathfinder.entity.FileInfo;
import cn.chenxinjie.pathfinder.entity.User;
import cn.chenxinjie.pathfinder.repository.FileInfoRepository;
import cn.chenxinjie.pathfinder.repository.FileRecycleBinRepository;
import cn.chenxinjie.pathfinder.repository.UserRepository;
import cn.chenxinjie.pathfinder.security.AuthUser;
import cn.chenxinjie.pathfinder.util.RedisTtlPolicy;
import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import cn.chenxinjie.uploadfile.core.service.TrustedUploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M1 回归：ownerChange 归属边界收紧。
 * 覆盖：目标用户存在性、非 ADMIN 不得公开化/提升为组织空间、
 * 目标组织/目标用户须在操作者可见域内；以及合法移交成功路径。
 */
class FileServiceOwnerChangeTest {

    private FileInfoRepository fileInfoRepository;
    private FileRecycleBinRepository recycleBinRepository;
    private OrgService orgService;
    private UserRepository userRepository;
    private FileService fileService;

    @BeforeEach
    void setUp() {
        fileInfoRepository = mock(FileInfoRepository.class);
        recycleBinRepository = mock(FileRecycleBinRepository.class);
        orgService = mock(OrgService.class);
        userRepository = mock(UserRepository.class);
        fileService = new FileService(
                fileInfoRepository,
                recycleBinRepository,
                orgService,
                new PathProperties(),
                mock(RedisTtlPolicy.class),
                mock(LogService.class),
                new tools.jackson.databind.ObjectMapper(),
                userRepository,
                mock(ResumableUploadService.class),
                mock(TrustedUploadService.class));
    }

    private FileInfo personalFile(long ownerId) {
        FileInfo f = new FileInfo();
        f.setId(1L);
        f.setOriginalName("a.txt");
        f.setSpaceType("PERSONAL");
        f.setOwnerId(ownerId);
        f.setCreatorId(ownerId);
        f.setStatus("READY");
        f.setDelFlag(0);
        f.setDiskStatus("READY");
        return f;
    }

    private AuthUser user(long id, String role, long orgId) {
        return new AuthUser(id, "u" + id, "用户" + id, role, orgId, 0);
    }

    private User nextUser(long id, long orgId) {
        User u = new User();
        u.setId(id);
        u.setOrgId(orgId);
        return u;
    }

    private void stubFile(FileInfo f) {
        when(fileInfoRepository.findById(anyLong())).thenReturn(Optional.of(f));
    }

    @Test
    void 目标归属用户不存在时拒绝() {
        FileInfo f = personalFile(111L);
        stubFile(f);
        AuthUser admin = user(1L, "ADMIN", 1L);
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        FileService.OwnerChangeForm form = new FileService.OwnerChangeForm();
        form.setSpaceType("PERSONAL");
        form.setOwnerId(999L);

        BizException ex = assertThrows(BizException.class,
                () -> fileService.ownerChange(1L, form, admin));
        assertEquals(404, ex.getStatus());
        assertEquals("目标归属用户不存在", ex.getMessage());
        verify(fileInfoRepository, never()).save(f);
    }

    @Test
    void 普通用户不得将个人文件公开化() {
        FileInfo f = personalFile(111L);
        stubFile(f);
        AuthUser normal = user(111L, "USER", 1L);

        FileService.OwnerChangeForm form = new FileService.OwnerChangeForm();
        form.setSpaceType("PUBLIC");

        BizException ex = assertThrows(BizException.class,
                () -> fileService.ownerChange(1L, form, normal));
        assertEquals(403, ex.getStatus());
        assertEquals("无权将文件公开化或提升为组织空间", ex.getMessage());
        verify(fileInfoRepository, never()).save(f);
    }

    @Test
    void 普通用户不得将个人文件提升为组织空间() {
        FileInfo f = personalFile(111L);
        stubFile(f);
        AuthUser normal = user(111L, "USER", 1L);
        when(orgService.get(2L)).thenReturn(null);

        FileService.OwnerChangeForm form = new FileService.OwnerChangeForm();
        form.setSpaceType("ORG");
        form.setOrgId(2L);

        BizException ex = assertThrows(BizException.class,
                () -> fileService.ownerChange(1L, form, normal));
        assertEquals(403, ex.getStatus());
        assertEquals("无权将文件公开化或提升为组织空间", ex.getMessage());
        verify(fileInfoRepository, never()).save(f);
    }

    @Test
    void 普通用户不得移交给可见组织外的用户() {
        FileInfo f = personalFile(111L);
        stubFile(f);
        AuthUser normal = user(111L, "USER", 1L);
        // 目标用户属于不可见组织 99
        when(userRepository.findById(222L)).thenReturn(Optional.of(nextUser(222L, 99L)));
        when(orgService.visibleOrgIds(normal)).thenReturn(Set.of(1L));

        FileService.OwnerChangeForm form = new FileService.OwnerChangeForm();
        form.setSpaceType("PERSONAL");
        form.setOwnerId(222L);

        BizException ex = assertThrows(BizException.class,
                () -> fileService.ownerChange(1L, form, normal));
        assertEquals(403, ex.getStatus());
        assertEquals("不能将文件移交给可见范围外的用户", ex.getMessage());
        verify(fileInfoRepository, never()).save(f);
    }

    @Test
    void 普通用户不得将文件转入不可见组织() {
        FileInfo f = new FileInfo();
        f.setId(1L);
        f.setOriginalName("a.txt");
        f.setSpaceType("ORG");
        f.setOwnerId(111L);
        f.setCreatorId(111L);
        f.setOrgId(1L);
        f.setStatus("READY");
        f.setDelFlag(0);
        f.setDiskStatus("READY");
        stubFile(f);
        AuthUser normal = user(111L, "USER", 1L);
        when(orgService.get(2L)).thenReturn(null);
        when(orgService.visibleOrgIds(normal)).thenReturn(Set.of(1L));

        FileService.OwnerChangeForm form = new FileService.OwnerChangeForm();
        form.setSpaceType("ORG");
        form.setOrgId(2L);

        BizException ex = assertThrows(BizException.class,
                () -> fileService.ownerChange(1L, form, normal));
        assertEquals(403, ex.getStatus());
        assertEquals("目标组织不在你的可见范围内", ex.getMessage());
        verify(fileInfoRepository, never()).save(f);
    }

    @Test
    void 管理员可将个人文件公开化() {
        FileInfo f = personalFile(111L);
        stubFile(f);
        AuthUser admin = user(1L, "ADMIN", 1L);

        FileService.OwnerChangeForm form = new FileService.OwnerChangeForm();
        form.setSpaceType("PUBLIC");

        fileService.ownerChange(1L, form, admin);
        assertEquals("PUBLIC", f.getSpaceType());
        verify(fileInfoRepository).save(f);
    }

    @Test
    void 普通用户可向可见组织内用户合法移交() {
        FileInfo f = personalFile(111L);
        stubFile(f);
        AuthUser normal = user(111L, "USER", 1L);
        when(userRepository.findById(222L)).thenReturn(Optional.of(nextUser(222L, 1L)));
        when(orgService.visibleOrgIds(normal)).thenReturn(Set.of(1L));

        FileService.OwnerChangeForm form = new FileService.OwnerChangeForm();
        form.setSpaceType("PERSONAL");
        form.setOwnerId(222L);

        fileService.ownerChange(1L, form, normal);
        assertEquals(222L, f.getOwnerId());
        verify(fileInfoRepository).save(f);
    }
}