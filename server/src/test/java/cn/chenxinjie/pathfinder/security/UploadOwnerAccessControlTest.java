package cn.chenxinjie.pathfinder.security;

import cn.chenxinjie.pathfinder.entity.FileInfo;
import cn.chenxinjie.pathfinder.repository.FileInfoRepository;
import cn.chenxinjie.uploadfile.core.security.AccessControl;
import cn.chenxinjie.uploadfile.core.security.AccessDecision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * rc.6 {@link AccessControl#decide} 迁移验证：已登录但无归属返回 403（而非旧 check() 的默认 401），
 * 归属人/管理员放行，无 SecurityContext 的内部调用放行。
 */
class UploadOwnerAccessControlTest {

    private FileInfoRepository repository;
    private UploadOwnerAccessControl accessControl;

    @BeforeEach
    void setUp() {
        repository = mock(FileInfoRepository.class);
        accessControl = new UploadOwnerAccessControl(repository);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void login(Long userId, String role) {
        AuthUser user = new AuthUser(userId, "u" + userId, "用户" + userId, role, 1L, 0);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private FileInfo fileOwnedBy(Long creatorId) {
        FileInfo f = new FileInfo();
        f.setCreatorId(creatorId);
        f.setUploadIdentifier("id-1");
        return f;
    }

    @Test
    void owner_isAllowed() {
        login(7L, "USER");
        when(repository.findFirstByUploadIdentifierAndDelFlag("id-1", 0))
                .thenReturn(Optional.of(fileOwnedBy(7L)));

        AccessDecision decision = accessControl.decide("id-1", AccessControl.ACTION_MERGE, null);
        assertTrue(decision.allowed());
    }

    @Test
    void admin_isAllowedEvenWhenNotOwner() {
        login(1L, "ADMIN");
        when(repository.findFirstByUploadIdentifierAndDelFlag("id-1", 0))
                .thenReturn(Optional.of(fileOwnedBy(7L)));

        assertTrue(accessControl.decide("id-1", AccessControl.ACTION_CANCEL, null).allowed());
    }

    @Test
    void authenticatedNonOwner_deniedWith403() {
        login(9L, "USER");
        when(repository.findFirstByUploadIdentifierAndDelFlag("id-1", 0))
                .thenReturn(Optional.of(fileOwnedBy(7L)));

        AccessDecision decision = accessControl.decide("id-1", AccessControl.ACTION_MERGE, null);
        assertFalse(decision.allowed());
        assertEquals(403, decision.statusCode(), "已认证但无归属应返回 403（区别于未认证的 401）");
    }

    @Test
    void unknownIdentifier_deniedWith403() {
        login(9L, "USER");
        when(repository.findFirstByUploadIdentifierAndDelFlag("id-1", 0)).thenReturn(Optional.empty());

        AccessDecision decision = accessControl.decide("id-1", AccessControl.ACTION_PROGRESS, null);
        assertFalse(decision.allowed());
        assertEquals(403, decision.statusCode());
    }

    @Test
    void blankIdentifier_deniedWith403() {
        login(9L, "USER");
        AccessDecision decision = accessControl.decide("  ", AccessControl.ACTION_UPLOAD, null);
        assertFalse(decision.allowed());
        assertEquals(403, decision.statusCode());
    }

    @Test
    void noSecurityContext_allowedForTrustedInternalCall() {
        AccessDecision decision = accessControl.decide("id-1", AccessControl.ACTION_MERGE, null);
        assertTrue(decision.allowed(), "服务内 confirm 回收等无会话调用应放行");
    }

    @Test
    void repeatedDecisions_reuseCachedOwnerWithoutQueryingAgain() {
        login(7L, "USER");
        when(repository.findFirstByUploadIdentifierAndDelFlag("id-1", 0))
                .thenReturn(Optional.of(fileOwnedBy(7L)));

        // 同一 identifier 的多次决策（分片/进度）应命中缓存，只查库一次（A1 优化）
        assertTrue(accessControl.decide("id-1", AccessControl.ACTION_UPLOAD, null).allowed());
        assertTrue(accessControl.decide("id-1", AccessControl.ACTION_PROGRESS, null).allowed());
        assertTrue(accessControl.decide("id-1", AccessControl.ACTION_MERGE, null).allowed());

        verify(repository, times(1)).findFirstByUploadIdentifierAndDelFlag("id-1", 0);
    }
}
