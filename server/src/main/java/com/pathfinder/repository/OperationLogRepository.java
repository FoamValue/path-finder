package com.pathfinder.repository;

import com.pathfinder.entity.OperationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDateTime;
import java.util.List;

public interface OperationLogRepository extends JpaRepository<OperationLog, Long>, JpaSpecificationExecutor<OperationLog> {

    List<OperationLog> findByCreatedAtBefore(LocalDateTime before);

    long deleteByCreatedAtBefore(LocalDateTime before);
}
