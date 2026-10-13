package vn.edu.ds03.user.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

@Entity
@Table(name = "users", uniqueConstraints = {
    @UniqueConstraint(name = "uq_users_email", columnNames = "email"),
    @UniqueConstraint(name = "uq_users_phone", columnNames = "phone")
})
public class User {
    public enum Role {
        PASSENGER, DRIVER, ADMIN
    }

    public enum UserStatus {
        ACTIVE, DISABLED
    }

    @Id
    @Column(nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private String id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;

    protected User() {
    }

    public User(String name, String email, String phone, String passwordHash, Role role) {
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    @PrePersist
    private void initialize() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        }
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public UserStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setStatus(UserStatus status) {
        this.status = status;
    }
}
