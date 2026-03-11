package com.example.jpa.production.repository;

import com.example.jpa.production.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {
}
