package cn.chenxinjie.pathfinder.security;

import cn.chenxinjie.pathfinder.entity.FileInfo;
import cn.chenxinjie.pathfinder.repository.FileInfoRepository;
import cn.chenxinjie.pathfinder.util.TtlCache;
import cn.chenxinjie.uploadfile.core.security.AccessControl;
import cn.chenxinjie.uploadfile.core.security.AccessDecision;
import org.springframework.stereotype.Component;

/**
 * 上传/下载任务级授权（组件 rc.6 AccessControl SPI，替代默认 PermitAllAccessControl）：
 * 把组件的任意 action（upload/progress/merge/mergeAsync/mergeStatus/cancel/download）
 * 绑定到上传任务的归属人/管理员，防止拿到 identifier 即可操作他人任务。凭据直接取当前会话
 * （SecurityContextHolder），与组件传入的 token 参数无关（PathFinder 不采用共享令牌）。
 *
 * <p>rc.6 起覆写 {@link #decide(String, String, String)}：已登录但无归属的调用返回
 * {@code AccessDecision.deny(403, ...)}（组件 Servlet 据此返回 403，与 MVC 路径一致），
 * 而非旧 {@code check()} 的默认 401；越权审计由 {@link UploadAccessAuditListener} 在决策点留痕。</p>
 *
 * <p>安全前提：{@code /upload}（及可选的 {@code /download}）已纳入 Spring Security 会话鉴权
 * （SecurityConfig），本检查是对「identifier 归属」的第二道防线。无 SecurityContext 的调用视为内部调用
 * （单元测试/服务内 confirm 回收），放行——外网请求不可能在该前提下到达组件服务。</p>
 *
 * <p>性能：组件对<b>每个分片请求</b>都调用本 {@link #decide}（gate），若每次都查库，500MB/5MB 上传将
 * 产生 100+ 次 DB 查询。故用 60s 有界 TTL 缓存 identifier → creatorId：同一任务的分片/进度请求命中
 * 缓存跳过查库。缓存的是不可变的 creatorId（归属变更改的是 ownerId），唯一失效场景是软删除，60s 窗口
 * 内风险可接受。</p>
 */
@Component
public class UploadOwnerAccessControl implements AccessControl {

    /** identifier → creatorId；上限 1 万条，TTL 60s。 */
    private final TtlCache<String, Long> ownerCache = new TtlCache<>(10_000, 60_000);

    private final FileInfoRepository fileInfoRepository;

    public UploadOwnerAccessControl(FileInfoRepository fileInfoRepository) {
        this.fileInfoRepository = fileInfoRepository;
    }

    @Override
    public AccessDecision decide(String identifier, String action, String token) {
        AuthUser user = SecurityUtil.currentOrNull();
        if (user == null) {
            return AccessDecision.allow();
        }
        if (identifier == null || identifier.isBlank()) {
            return AccessDecision.deny(403, "missing identifier for upload action: " + action);
        }
        Long creatorId = ownerCache.get(identifier);
        if (creatorId == null) {
            FileInfo file = fileInfoRepository.findFirstByUploadIdentifierAndDelFlag(identifier, 0).orElse(null);
            if (file == null) {
                return AccessDecision.deny(403, "no permission for upload action: " + action
                        + ", identifier: " + identifier);
            }
            creatorId = file.getCreatorId();
            if (creatorId != null) {
                ownerCache.put(identifier, creatorId);
            }
        }
        boolean allowed = user.isAdmin() || user.getId().equals(creatorId);
        if (!allowed) {
            return AccessDecision.deny(403, "no permission for upload action: " + action
                    + ", identifier: " + identifier);
        }
        return AccessDecision.allow();
    }
}
