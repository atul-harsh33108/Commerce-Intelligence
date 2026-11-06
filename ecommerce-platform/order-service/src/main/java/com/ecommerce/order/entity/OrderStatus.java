package com.ecommerce.order.entity;

/**
 * One vocabulary, two populations.
 *
 * <p>This table holds ~100K historical Olist orders and every order placed since. Rather than two
 * status columns or a "historical" flag, both use one enum — because the analytics side has to
 * aggregate across them, and "how many orders were cancelled" must not need a union of two
 * different spellings.
 *
 * <h2>The Olist statuses</h2>
 *
 * <p>{@link #CREATED} … {@link #UNAVAILABLE} are the source dataset's own vocabulary, uppercased by
 * the loader. They describe a fulfilment lifecycle that this platform does not run — nothing here
 * ships a parcel — and they exist so historical rows keep their meaning.
 *
 * <h2>The saga statuses</h2>
 *
 * <p>{@link #PENDING}, {@link #AWAITING_PAYMENT} and {@link #CONFIRMED} are the live order flow's
 * own. A new order is PENDING until inventory answers, AWAITING_PAYMENT until payment answers, then
 * CONFIRMED — or CANCELED with a reason.
 *
 * <h2>CANCELED, with one L</h2>
 *
 * <p>British English would spell it CANCELLED and so would most Java codebases. The source data
 * spells it {@code canceled}, and the choice was between translating on the way in (a mapping that
 * has to be remembered in the loader, in the warehouse ETL, and in every query written against
 * either) or adopting the source's spelling everywhere. <strong>One spelling that looks slightly
 * wrong beats two that are each right somewhere.</strong>
 */
public enum OrderStatus {

    // --- Olist's fulfilment vocabulary, for the ~100K historical rows -----------------------------
    CREATED,
    APPROVED,
    PROCESSING,
    INVOICED,
    SHIPPED,
    DELIVERED,
    UNAVAILABLE,

    // --- The live saga's vocabulary ---------------------------------------------------------------
    /** Written, durable, and announced. Nothing downstream has answered yet. */
    PENDING,
    /** Inventory reserved. Waiting for payment to succeed or fail. */
    AWAITING_PAYMENT,
    /** Paid. The reservation has become a real removal from stock. */
    CONFIRMED,

    // --- Shared by both ---------------------------------------------------------------------------
    /** Either an Olist cancellation or a saga that compensated. `failureReason` distinguishes them. */
    CANCELED;

    /** True for the three states a live saga can still move out of. */
    public boolean isInFlight() {
        return this == PENDING || this == AWAITING_PAYMENT;
    }
}
