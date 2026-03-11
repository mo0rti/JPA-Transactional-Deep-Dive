package com.example.jpa.transactional.repository;

import com.example.jpa.transactional.entity.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
}
