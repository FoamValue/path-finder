package cn.chenxinjie.pathfinder.repository;

import cn.chenxinjie.pathfinder.entity.FileRecycleBin;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FileRecycleBinRepository extends JpaRepository<FileRecycleBin, Long> {

    Optional<FileRecycleBin> findByFileId(Long fileId);

    List<FileRecycleBin> findByExpireAtBefore(LocalDateTime before);

    long countByExpireAtBefore(LocalDateTime before);

    void deleteByFileId(Long fileId);

    /**
     * 回收站分页（B1）：与 file_info 关联分页，仅返回文件记录仍存在的行，
     * 由数据库层 LIMIT/OFFSET，替代「全表加载 + 逐条 findById」。
     */
    @Query(value = "SELECT rb FROM FileRecycleBin rb, FileInfo f WHERE rb.fileId = f.id",
            countQuery = "SELECT COUNT(rb) FROM FileRecycleBin rb, FileInfo f WHERE rb.fileId = f.id")
    Page<FileRecycleBin> pageWithFile(Pageable pageable);

    /**
     * 回收站分页（B1，数据权限）：仅返回当前用户可见范围（PUBLIC / 本人 PERSONAL / 可见部门 DEPT）。
     */
    @Query(value = "SELECT rb FROM FileRecycleBin rb, FileInfo f WHERE rb.fileId = f.id "
            + "AND (f.spaceType = 'PUBLIC' "
            + "OR (f.spaceType = 'PERSONAL' AND f.ownerId = :userId) "
            + "OR (f.spaceType = 'DEPT' AND f.deptId IN :deptIds))",
            countQuery = "SELECT COUNT(rb) FROM FileRecycleBin rb, FileInfo f WHERE rb.fileId = f.id "
            + "AND (f.spaceType = 'PUBLIC' "
            + "OR (f.spaceType = 'PERSONAL' AND f.ownerId = :userId) "
            + "OR (f.spaceType = 'DEPT' AND f.deptId IN :deptIds))")
    Page<FileRecycleBin> pageVisibleTo(@Param("userId") Long userId,
                                       @Param("deptIds") Collection<Long> deptIds,
                                       Pageable pageable);
}
