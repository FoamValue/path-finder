package cn.chenxinjie.pathfinder.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 系统用户。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "sys_user")
public class User extends BaseEntity {

    @Column(name = "username", nullable = false, unique = true, length = 64)
    private String username;

    @Column(name = "password", nullable = false, length = 128)
    private String password;

    @Column(name = "real_name", nullable = false, length = 64)
    private String realName;

    @Column(name = "dept_id", nullable = false)
    private Long deptId;

    @Column(name = "status", nullable = false)
    private Integer status = 1;

    @Column(name = "must_change_password", nullable = false)
    private Integer mustChangePassword = 1;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "del_flag", nullable = false)
    private Integer delFlag = 0;
}
