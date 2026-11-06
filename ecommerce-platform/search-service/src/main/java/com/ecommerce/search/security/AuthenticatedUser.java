package com.ecommerce.search.security;

/**
 * Who the caller is, according to their token.
 *
 * <p>The id comes from the {@code uid} claim rather than a database lookup, because this service has
 * no users table and no route to one (database-per-service). The identity used for any ownership
 * check is the one from the <em>token</em>, never one from a path variable or a header: an endpoint
 * that takes a user id in its URL has to check that the caller may read it, and the check that is
 * never written is the check that is never wrong.
 */
public record AuthenticatedUser(Long id, String username, String role) {
}
