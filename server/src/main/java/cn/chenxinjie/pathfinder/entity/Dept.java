package cn.chenxinjie.pathfinder.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 部门树节点。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "sys_dept")
public class Dept extends BaseEntity {

    @Column(name = "parent_id", nullable = false)
    private Long parentId = 0L;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "status", nullable = false)
    private Integer status = 1;

    @Column(name = "del_flag", nullable = false)
    private Integer delFlag = 0;
}
