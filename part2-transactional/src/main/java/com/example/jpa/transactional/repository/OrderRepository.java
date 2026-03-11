package com.example.jpa.transactional.repository;

import com.example.jpa.transactional.entity.Order;
import com.example.jpa.transactional.entity.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * @Modifying without clearAutomatically — the Persistence Context
     * keeps stale entities after this runs.
     *
     * No @Transactional here — this method relies on being called from
     * within a service-level @Transactional method. If called without
     * an active transaction, JPA throws TransactionRequiredException.
     */
    @Modifying
    @Query("UPDATE Order o SET o.status = :status WHERE o.id = :id")
    int updateStatusById(@Param("id") Long id, @Param("status") OrderStatus status);

    /**
     * @Modifying with clearAutomatically — the Persistence Context is
     * cleared after this runs, so subsequent reads go to the database.
     *
     * Has its own @Transactional so it can be called directly without
     * an existing transaction context.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE Order o SET o.status = :status WHERE o.status = 'CREATED' AND o.createdAt < :cutoff")
    int cancelStaleOrders(@Param("status") OrderStatus status, @Param("cutoff") LocalDateTime cutoff);
}
