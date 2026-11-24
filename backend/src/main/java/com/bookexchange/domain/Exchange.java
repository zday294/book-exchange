package com.bookexchange.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "exchange")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for JPA
public class Exchange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 12)
    private String code;

    @Column(nullable = false, length = 200)
    @Setter
    private String name;

    @Column(length = 1000)
    @Setter
    private String description;

    @Column(name = "created_by_name", length = 100)
    @Setter
    private String createdByName;

    @Column(nullable = false)
    @Setter
    private boolean listed = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Setter
    private ExchangeStatus status = ExchangeStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Setter
    private ExchangeMode mode = ExchangeMode.SUBMIT;

    /** SHA-256 of the host token; null for exchanges created before host tokens existed (no host). */
    @Column(name = "host_token_hash", length = 64)
    @Setter
    private String hostTokenHash;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    public Exchange(String code, String name) {
        this.code = code;
        this.name = name;
    }
}
