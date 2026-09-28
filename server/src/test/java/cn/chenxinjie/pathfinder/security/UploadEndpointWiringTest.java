package cn.chenxinjie.pathfinder.security;

import cn.chenxinjie.uploadfile.core.security.AccessControl;
import cn.chenxinjie.uploadfile.core.security.AccessControlListener;
import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import cn.chenxinjie.uploadfile.core.service.TrustedUploadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rc.6 官方 HTTP 层装配冒烟：starter 自动装配 core 服务并注册 UploadServlet(/upload)；
 * /download 默认关闭；PathFinder 的 AccessControl/审计监听器覆盖组件默认实现。
 */
@SpringBootTest
@ActiveProfiles("test")
class UploadEndpointWiringTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void coreServiceAutoWiredByStarter() {
        assertNotNull(context.getBean(ResumableUploadService.class), "starter 应自动装配 ResumableUploadService");
    }

    @Test
    void uploadServletRegisteredAtUploadPath() {
        Map<String, ServletRegistrationBean> beans =
                context.getBeansOfType(ServletRegistrationBean.class);
        assertTrue(beans.values().stream().anyMatch(b -> b.getUrlMappings().contains("/upload")),
                "官方 UploadServlet 应注册在 /upload");
    }

    @Test
    void downloadServletNotRegisteredByDefault() {
        Map<String, ServletRegistrationBean> beans =
                context.getBeansOfType(ServletRegistrationBean.class);
        assertFalse(beans.values().stream().anyMatch(b -> b.getUrlMappings().contains("/download")),
                "endpoint.download-enabled=false 时不应注册 /download（最小暴露）");
    }

    @Test
    void accessControlIsPathfinderOwnerPolicy() {
        assertInstanceOf(UploadOwnerAccessControl.class, context.getBean(AccessControl.class),
                "UploadOwnerAccessControl 应覆盖组件默认 PermitAllAccessControl");
    }

    @Test
    void auditListenerRegistered() {
        assertInstanceOf(UploadAccessAuditListener.class, context.getBean(AccessControlListener.class));
    }

    @Test
    void trustedUploadServiceAutoWiredByStarter() {
        assertNotNull(context.getBean(TrustedUploadService.class),
                "rc.8 starter 应自动装配受信读门面 TrustedUploadService（本工程不再手写 UploadTrustedConfig）");
    }
}
