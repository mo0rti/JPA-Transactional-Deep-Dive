package com.example.jpa.transactional.service;

import com.example.jpa.transactional.entity.AuditEvent;
import com.example.jpa.transactional.repository.AuditEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audit service demonstrating REQUIRES_NEW propagation.
 *
 * Audit events should survive even if the calling transaction rolls back.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository auditEventRepository;

    public AuditService(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    /**
     * REQUIRES_NEW: runs in its own independent transaction.
     * Even if the caller's transaction rolls back, this audit event is committed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logEvent(String event) {
        log.info("AuditService.logEvent() — running in NEW transaction");
        auditEventRepository.save(new AuditEvent(event));
    }

    /**
     * REQUIRED (default): joins the caller's transaction.
     * If this method throws, the entire transaction is marked for rollback.
     */
    @Transactional
    public void logEventJoined(String event) {
        log.info("AuditService.logEventJoined() — running in SAME transaction as caller");
        auditEventRepository.save(new AuditEvent(event));
        throw new RuntimeException("Audit failed! This marks the ENTIRE transaction for rollback.");
    }
}
