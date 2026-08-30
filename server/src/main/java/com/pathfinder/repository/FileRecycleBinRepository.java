package com.pathfinder.repository;

import com.pathfinder.entity.FileRecycleBin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface FileRecycleBinRepository extends JpaRepository<FileRecycleBin, Long> {

    Optional<FileRecycleBin> findByFileId(Long fileId);

    List<FileRecycleBin> findByExpireAtBefore(LocalDateTime before);

    long countByExpireAtBefore(LocalDateTime before);

    void deleteByFileId(Long fileId);
}
