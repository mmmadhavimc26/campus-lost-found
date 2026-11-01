package edu.campus.lostfound;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

interface Users extends JpaRepository<CampusUser, Long> {
    Optional<CampusUser> findByEmail(String email);
    boolean existsByEmail(String email);
}
interface Reports extends JpaRepository<ItemReport, Long> {
    List<ItemReport> findTop200ByOrderByCreatedAtDesc();
    List<ItemReport> findByOwnerIdOrderByCreatedAtDesc(Long id);
    List<ItemReport> findByCategoryAndStatus(String category, String status);
}
interface Claims extends JpaRepository<ItemClaim, Long> {
    boolean existsByReportIdAndClaimantId(Long report, Long user);
    List<ItemClaim> findByClaimantIdOrderByCreatedAtDesc(Long user);
}
