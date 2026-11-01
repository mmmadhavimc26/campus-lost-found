package edu.campus.lostfound;

import jakarta.persistence.*;
import java.time.*;

@Entity @Table(name="app_users")
class CampusUser {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(nullable=false) String name;
    @Column(nullable=false, unique=true) String email;
    @Column(name="password_hash", nullable=false) String passwordHash;
    boolean enabled = true;
    protected CampusUser() {}
    CampusUser(String name, String email, String hash) { this.name=name; this.email=email; this.passwordHash=hash; }
}

@Entity @Table(name="reports")
class ItemReport {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(name="owner_id", nullable=false) Long ownerId;
    String kind;
    String title;
    String category;
    String location;
    @Column(name="event_date") LocalDate eventDate;
    @Column(length=700) String description;
    @Column(name="created_at") Instant createdAt = Instant.now();
    String status = "OPEN";
    protected ItemReport() {}
    ItemReport(Long owner, Api.ReportInput input) {
        ownerId=owner; kind=input.kind(); title=input.title().strip(); category=input.category();
        location=input.location(); eventDate=input.eventDate(); description=input.description().strip();
    }
}

@Entity @Table(name="claims", uniqueConstraints=@UniqueConstraint(columnNames={"report_id","claimant_id"}))
class ItemClaim {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(name="report_id") Long reportId;
    @Column(name="claimant_id") Long claimantId;
    @Column(name="private_details", length=1500) String privateDetails;
    @Column(name="created_at") Instant createdAt = Instant.now();
    String status = "PENDING";
    protected ItemClaim() {}
    ItemClaim(Long report, Long user, String details) { reportId=report; claimantId=user; privateDetails=details.strip(); }
}
