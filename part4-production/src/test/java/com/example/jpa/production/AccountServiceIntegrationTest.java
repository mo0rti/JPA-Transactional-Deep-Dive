package com.example.jpa.production;

import com.example.jpa.production.entity.Account;
import com.example.jpa.production.repository.AccountRepository;
import com.example.jpa.production.service.AccountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test using Testcontainers with real PostgreSQL.
 *
 * No @Transactional on the test class — data is committed for real,
 * and cleaned up in @AfterEach. This tests actual transaction behavior.
 */
@SpringBootTest
@Testcontainers
class AccountServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    @AfterEach
    void cleanup() {
        accountRepository.deleteAll();
    }

    @Test
    void transfer_shouldMoveMoneyBetweenAccounts() {
        Account alice = accountService.createAccount("Alice", new BigDecimal("1000.00"));
        Account bob = accountService.createAccount("Bob", new BigDecimal("500.00"));

        accountService.transfer(alice.getId(), bob.getId(), new BigDecimal("300.00"));

        Account aliceAfter = accountService.findById(alice.getId());
        Account bobAfter = accountService.findById(bob.getId());

        assertThat(aliceAfter.getBalance()).isEqualByComparingTo("700.00");
        assertThat(bobAfter.getBalance()).isEqualByComparingTo("800.00");
    }

    @Test
    void transfer_shouldRollbackOnInsufficientFunds() {
        Account alice = accountService.createAccount("Alice", new BigDecimal("100.00"));
        Account bob = accountService.createAccount("Bob", new BigDecimal("500.00"));

        assertThatThrownBy(() ->
                accountService.transfer(alice.getId(), bob.getId(), new BigDecimal("200.00"))
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("Insufficient funds");

        // Verify rollback: balances unchanged
        Account aliceAfter = accountService.findById(alice.getId());
        Account bobAfter = accountService.findById(bob.getId());

        assertThat(aliceAfter.getBalance()).isEqualByComparingTo("100.00");
        assertThat(bobAfter.getBalance()).isEqualByComparingTo("500.00");
    }

    @Test
    void createAccount_shouldPersistToDatabase() {
        Account account = accountService.createAccount("Charlie", new BigDecimal("750.00"));

        // Data is committed — verify with a fresh query
        Account found = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(found.getOwner()).isEqualTo("Charlie");
        assertThat(found.getBalance()).isEqualByComparingTo("750.00");
    }
}
