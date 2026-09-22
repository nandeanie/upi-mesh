package com.demo.upimesh.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * Simulated bank account.
 *
 * @Version provides optimistic locking — if two threads concurrently try to
 * update the same account, the second will get an OptimisticLockException
 * rather than silently corrupting the balance. This is defense-in-depth;
 * the idempotency layer should prevent double-settlement before we ever reach
 * the DB, but belts-and-suspenders matter in fintech.
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    private String vpa;           // Virtual Payment Address, e.g. "alice@demo"

    @Column(nullable = false)
    private String holderName;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Version
    private Long version;

    public Account() {}

    public Account(String vpa, String holderName, BigDecimal balance) {
        this.vpa = vpa;
        this.holderName = holderName;
        this.balance = balance;
    }

    public String getVpa()                   { return vpa; }
    public void   setVpa(String vpa)         { this.vpa = vpa; }

    public String getHolderName()                      { return holderName; }
    public void   setHolderName(String holderName)     { this.holderName = holderName; }

    public BigDecimal getBalance()                     { return balance; }
    public void       setBalance(BigDecimal balance)   { this.balance = balance; }

    public Long getVersion()                 { return version; }
    public void setVersion(Long version)     { this.version = version; }
}
