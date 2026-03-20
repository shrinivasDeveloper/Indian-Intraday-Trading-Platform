package com.trading.auth.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.*;

@Entity
@Table(name = "zerodha_tokens",
    uniqueConstraints = @UniqueConstraint(columnNames = {"account_id", "token_date"}))
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ZerodhaToken {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id",   nullable = false) private String    accountId;
    @Column(name = "access_token", nullable = false, length = 1000) private String accessToken;
    @Column(name = "public_token", length = 1000)    private String    publicToken;
    @Column(name = "user_id")                        private String    userId;
    @Column(name = "token_date",   nullable = false) private LocalDate tokenDate;
    @Column(name = "expires_at")                     private Instant   expiresAt;
    @Column(name = "created_at")                     private Instant   createdAt;
}
