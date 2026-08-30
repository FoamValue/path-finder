package com.pathfinder.repository;

import com.pathfinder.entity.Dept;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeptRepository extends JpaRepository<Dept, Long> {

    List<Dept> findByDelFlagOrderBySortOrderAsc(Integer delFlag);

    List<Dept> findByParentIdAndDelFlag(Long parentId, Integer delFlag);

    boolean existsByParentIdAndDelFlag(Long parentId, Integer delFlag);
}
