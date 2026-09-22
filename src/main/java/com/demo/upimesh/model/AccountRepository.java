package com.demo.upimesh.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, String> {

    /**
     * Pessimistic write lock for use inside settlement transaction.
     * Prevents phantom concurrent debits if optimistic locking is ever
     * disabled or bypassed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.vpa = :vpa")
    Optional<Account> findByVpaForUpdate(String vpa);
}
