package cn.chenxinjie.pathfinder.repository;

import cn.chenxinjie.pathfinder.entity.Org;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrgRepository extends JpaRepository<Org, Long> {

    List<Org> findByDelFlagOrderBySortOrderAsc(Integer delFlag);

    List<Org> findByParentIdAndDelFlag(Long parentId, Integer delFlag);

    boolean existsByParentIdAndDelFlag(Long parentId, Integer delFlag);
}
