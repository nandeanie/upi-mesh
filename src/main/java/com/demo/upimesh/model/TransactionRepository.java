package com.demo.upimesh.model;

import org.springframework.data.jpa.repository.JpaRepository;
<<<<<<< HEAD
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
=======
>>>>>>> 1252be4f882ee6f81234b00d40dc47dd23416d88
import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findTop50ByOrderByIdDesc();
    boolean existsByPacketHash(String packetHash);
    List<Transaction> findByStatusOrderByIdDesc(Transaction.Status status);
    long countByStatus(Transaction.Status status);
<<<<<<< HEAD

    /** Total ₹ moved by transactions in the given status; null when there are none. */
    @Query("SELECT SUM(t.amount) FROM Transaction t WHERE t.status = :status")
    BigDecimal sumAmountByStatus(@Param("status") Transaction.Status status);
=======
>>>>>>> 1252be4f882ee6f81234b00d40dc47dd23416d88
}
