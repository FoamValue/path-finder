package cn.chenxinjie.pathfinder.security;

import cn.chenxinjie.pathfinder.entity.FileInfo;
import cn.chenxinjie.pathfinder.repository.FileInfoRepository;
import cn.chenxinjie.uploadfile.core.exception.AccessDeniedException;
import cn.chenxinjie.uploadfile.core.security.AccessControl;
import org.springframework.stereotype.Component;

/**
 * 上传任务级授权（组件 rc.3 AccessControl SPI 接入，替代默认 PermitAllAccessControl）：
 * 把 /upload 的任意 action 绑定到上传任务的归属人/管理员，防止拿到 identifier 即可
 * merge/cancel 他人任务的越权。凭据直接取当前会话（SecurityContextHolder），
 * 与组件传入的 token 参数无关（PathFinder 不采用共享令牌）。
 *
 * <p>安全前提：{@code /upload} 已纳入 Spring Security 会话鉴权（SecurityConfig），
 * 本检查是对「identifier 归属」的第二道防线。无 SecurityContext 的调用视为内部调用
 * （单元测试/服务内 confirm 回收），放行——外网请求不可能在该前提下到达组件服务。</p>
 */
@Component
public class UploadOwnerAccessControl implements AccessControl {

    private final FileInfoRepository fileInfoRepository;

    public UploadOwnerAccessControl(FileInfoRepository fileInfoRepository) {
        this.fileInfoRepository = fileInfoRepository;
    }

    @Override
    public void check(String identifier, String action, String token) {
        AuthUser user = SecurityUtil.currentOrNull();
        if (user == null) {
            return;
        }
        if (identifier == null || identifier.isBlank()) {
            throw denied(action, null);
        }
        FileInfo file = fileInfoRepository.findFirstByUploadIdentifierAndDelFlag(identifier, 0).orElse(null);
        boolean allowed = file != null
                && (user.isAdmin() || file.getCreatorId().equals(user.getId()));
        if (!allowed) {
            throw denied(action, identifier);
        }
    }

    private AccessDeniedException denied(String action, String identifier) {
        return new AccessDeniedException("no permission for upload action: " + action
                + (identifier != null ? ", identifier: " + identifier : ""));
    }
}
