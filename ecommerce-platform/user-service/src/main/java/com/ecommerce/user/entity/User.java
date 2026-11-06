package com.ecommerce.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * A person who can log in, and — for the seeded ones — the Olist customer they correspond to.
 *
 * <h2>Two populations in one table</h2>
 *
 * <p>Rows arrive by two routes and it is worth being precise about the difference:
 *
 * <ul>
 *   <li><strong>Registered</strong> — created through {@code POST /api/auth/register} with a real
 *       password, hashed per user. {@code customerKey} is null.</li>
 *   <li><strong>Seeded</strong> — created by the Olist loader from a dataset that is anonymised and
 *       contains no names, emails or passwords. Username is derived from the customer key and
 *       <strong>every seeded row shares one bcrypt hash</strong>. That is acceptable for accounts
 *       that correspond to no real person and would be a serious flaw in anything else; it is
 *       recorded in docs/data-setup.md rather than left to be discovered.</li>
 * </ul>
 *
 * <p>{@code customerKey} is unique and nullable, which is what lets one table hold both: the unique
 * constraint makes the loader idempotent, and Postgres treats NULLs as distinct so any number of
 * registered users can have none.
 *
 * <h2>The `password` column holds a hash, and the name is a lie worth keeping</h2>
 *
 * <p>Called {@code password} because that is what every reader expects and what Spring Security's
 * {@code UserDetails} contract calls it. It contains a bcrypt digest — 60 characters beginning
 * {@code $2a$} — and the plaintext exists only inside the request that created it.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    @Column(nullable = false, unique = true, length = 200)
    private String email;

    /** A bcrypt hash. Never a password, despite the column name. */
    @Column(nullable = false, length = 100)
    private String password;

    /**
     * {@code EnumType.STRING}, never {@code ORDINAL}.
     *
     * <p>Ordinal stores the enum's position, so inserting a value into the middle of the enum
     * silently reinterprets every existing row — the classic way a database full of USERs becomes a
     * database full of ADMINs. The cost is a few bytes per row.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Role role = Role.USER;

    /** The Olist {@code customer_id} for seeded rows; null for registered ones. */
    @Column(name = "customer_key", unique = true, length = 64)
    private String customerKey;

    /**
     * The Olist {@code customer_unique_id} — the actual person across orders.
     *
     * <p>Not unique here, and that is the whole point of carrying both: {@code customer_key} is one
     * per order, {@code customer_unique_key} is one per human. Joining analytics on the wrong one
     * turns ~96K real customers into ~99K fake ones and makes every repeat-purchase metric zero.
     */
    @Column(name = "customer_unique_key", length = 64)
    private String customerUniqueKey;

    @Column(length = 100)
    private String city;

    /** The two-letter Brazilian federative unit. The warehouse's geography dimension keys off it. */
    @Column(length = 4)
    private String state;

    @Column(name = "zip_prefix", length = 12)
    private String zipPrefix;

    @Version
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
