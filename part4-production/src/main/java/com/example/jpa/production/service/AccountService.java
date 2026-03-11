package com.example.jpa.production.service;

import com.example.jpa.production.entity.Account;
import com.example.jpa.production.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Demonstrates clean transactional service design.
 */
@Service
@Transactional(readOnly = true)
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional
    public Account createAccount(String owner, BigDecimal initialBalance) {
        return accountRepository.save(new Account(owner, initialBalance));
    }

    @Transactional(rollbackFor = Exception.class)
    public void transfer(Long fromId, Long toId, BigDecimal amount) {
        log.info("Transferring {} from account {} to account {}", amount, fromId, toId);

        Account from = accountRepository.findById(fromId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + fromId));
        Account to = accountRepository.findById(toId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + toId));

        from.debit(amount);
        to.credit(amount);

        log.info("Transfer complete: from={}, to={}", from, to);
        // Dirty checking saves both accounts at commit
    }

    public Account findById(Long id) {
        return accountRepository.findById(id).orElseThrow();
    }
}
