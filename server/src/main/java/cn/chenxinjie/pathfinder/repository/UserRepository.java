package cn.chenxinjie.pathfinder.repository;

import cn.chenxinjie.pathfinder.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsernameAndDelFlag(String username, Integer delFlag);

    Optional<User> findByIdAndDelFlag(Long id, Integer delFlag);

    Page<User> findByDelFlag(Integer delFlag, Pageable pageable);

    Page<User> findByDelFlagAndDeptIdIn(Integer delFlag, List<Long> deptIds, Pageable pageable);

    Page<User> findByDelFlagAndRealNameContaining(Integer delFlag, String keyword, Pageable pageable);

    boolean existsByUsername(String username);
}
