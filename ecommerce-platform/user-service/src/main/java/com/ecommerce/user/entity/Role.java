package com.ecommerce.user.entity;

/**
 * The closed set of roles, owned here and nowhere else.
 *
 * <p>No other service has this enum, deliberately. They read the {@code role} claim as a
 * {@code String}, so adding a role here does not force five other services to rebuild — and an
 * unrecognised role in a downstream service simply matches no authorization rule, which is a 403.
 * Sharing the enum would trade that for a lockstep release between services that otherwise have
 * nothing to do with each other (D2).
 *
 * <p>Two values, not a hierarchy. Spring Security roles are flat: {@code hasRole("USER")} does not
 * admit an ADMIN, which is why every rule that both may use names both explicitly.
 */
public enum Role {
    USER,
    ADMIN
}
