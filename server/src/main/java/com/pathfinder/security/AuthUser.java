package com.pathfinder.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 认证通过后的当前用户上下文（存放于 SecurityContext Principal）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthUser {

    private Long id;
    private String username;
    private String realName;
    private String roleCode;
    private Long deptId;
    private Integer mustChangePassword;

    public boolean isAdmin() {
        return "ADMIN".equals(roleCode);
    }

    public boolean isDeptAdmin() {
        return "DEPT_ADMIN".equals(roleCode);
    }
}
