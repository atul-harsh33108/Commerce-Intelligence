package com.ecommerce.inventory.service;

import com.ecommerce.inventory.dto.InventoryDtos.ReserveLine;
import com.ecommerce.inventory.dto.InventoryDtos.StockResponse;
import com.ecommerce.inventory.entity.InsufficientStockException;
import com.ecommerce.inventory.entity.ProcessedEvent;
import com.ecommerce.inventory.entity.Reservation;
import com.ecommerce.inventory.entity.StockItem;
import com.ecommerce.inventory.exception.ResourceNotFoundException;
import com.ecommerce.inventory.repository.ProcessedEventRepository;
import com.ecommerce.inventory.repository.ReservationRepository;
import com.ecommerce.inventory.repository.StockItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * Reserve, release, confirm. The three things that can happen to stock.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryService {

    private final StockItemRepository stock;
    private final ReservationRepository reservations;
    private final ProcessedEventRepository processed;

    @Transactional(readOnly = true)
    public StockResponse byProductKey(String productKey) {
        return stock.findByProductKey(productKey).map(StockResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("No stock record for " + productKey));
    }

    @Transactional(readOnly = true)
    public List<StockResponse> byProductKeys(List<String> keys) {
        return stock.findByProductKeyIn(keys).stream().map(StockResponse::from).toList();
    }

    /**
     * Reserve every line of an order, or none of them.
     *
     * <h2>All-or-nothing, and the sort that makes it safe</h2>
     *
     * <p>The lines are sorted by product key before anything is touched. That is a
     * <strong>deadlock-avoidance</strong> measure, not tidiness: two orders containing the same two
     * products in opposite sequence will each hold the row the other wants, and Postgres resolves
     * that by killing one transaction. Taking rows in a globally consistent order means the second
     * transaction waits instead of dying. It is the cheapest deadlock fix there is — a comparator —
     * and it is invisible until concurrency is high enough to matter.
     *
     * <p>The whole method is one transaction, so a failure on the third line rolls back the first
     * two. There is no partial reservation state to compensate for, which is what makes the saga's
     * compensating action simple: release everything for this order, or nothing.
     *
     * <h2>The retry</h2>
     *
     * <p>{@code @Retryable} on the optimistic-lock failure only. A version conflict means somebody
     * else changed the row while this transaction was deciding — re-reading and retrying succeeds,
     * because the second attempt sees the new value. Retrying an {@link InsufficientStockException}
     * would be pointless (the answer will not change) and retrying a constraint violation would be
     * dangerous, so both are excluded by naming exactly one class.
     *
     * <p>{@code REQUIRES_NEW} is deliberately <em>not</em> used: the retry has to start a fresh
     * transaction, and Spring Retry's interceptor sits outside the transactional proxy only because
     * this method is called from another bean. Calling it from within this class would bypass both
     * proxies — the classic self-invocation trap.
     */
    @Retryable(retryFor = org.springframework.orm.ObjectOptimisticLockingFailureException.class,
            maxAttempts = 4, backoff = @Backoff(delay = 25, multiplier = 2.0))
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Reservation> reserve(String orderKey, List<ReserveLine> lines) {
        List<ReserveLine> ordered = lines.stream()
                .sorted(Comparator.comparing(ReserveLine::productKey))
                .toList();

        // An existing reservation for this order means a redelivery got past the event guard (or
        // this is a REST retry). Returning what is already there is the idempotent answer; reserving
        // again would take the stock twice.
        List<Reservation> existing = reservations.findByOrderKey(orderKey);
        if (!existing.isEmpty()) {
            log.info("Order {} already has {} reservation(s); returning them unchanged",
                    orderKey, existing.size());
            return existing;
        }

        List<Reservation> created = new java.util.ArrayList<>(ordered.size());
        for (ReserveLine line : ordered) {
            StockItem item = stock.findByProductKey(line.productKey())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "No stock record for " + line.productKey()));

            item.reserve(line.quantity());   // throws InsufficientStockException

            created.add(reservations.save(Reservation.builder()
                    .orderKey(orderKey)
                    .productKey(line.productKey())
                    .quantity(line.quantity())
                    .status(Reservation.Status.RESERVED)
                    .build()));
        }

        log.info("Reserved {} line(s) for order {}", created.size(), orderKey);
        return created;
    }

    /**
     * Compensation: put back everything this order took.
     *
     * <p>Idempotent by state rather than by a guard table — a reservation already RELEASED is
     * skipped. Compensation is the code most likely to run twice (it fires on a failure, and
     * failures come with retries), so "already done" has to be a normal outcome rather than an
     * error.
     */
    @Transactional
    public int release(String orderKey) {
        List<Reservation> active = reservations.findByOrderKeyAndStatus(
                orderKey, Reservation.Status.RESERVED);

        for (Reservation reservation : active) {
            stock.findByProductKey(reservation.getProductKey())
                    .ifPresent(item -> item.release(reservation.getQuantity()));
            reservation.setStatus(Reservation.Status.RELEASED);
        }

        if (!active.isEmpty()) {
            log.info("Released {} reservation(s) for order {}", active.size(), orderKey);
        }
        return active.size();
    }

    /** The order was paid: the reserved units leave the warehouse for good. */
    @Transactional
    public int confirm(String orderKey) {
        List<Reservation> active = reservations.findByOrderKeyAndStatus(
                orderKey, Reservation.Status.RESERVED);

        for (Reservation reservation : active) {
            stock.findByProductKey(reservation.getProductKey())
                    .ifPresent(item -> item.confirm(reservation.getQuantity()));
            reservation.setStatus(Reservation.Status.CONFIRMED);
        }

        if (!active.isEmpty()) {
            log.info("Confirmed {} reservation(s) for order {}", active.size(), orderKey);
        }
        return active.size();
    }

    /**
     * Records that an event has been handled, and says whether this is the first time.
     *
     * <p>Called from inside the listener's transaction so the guard and the effect commit together.
     * The insert is what does the work: a duplicate primary key means somebody already handled it,
     * and the caller skips. Checking first with an {@code existsById} would be a check-then-act race
     * against another consumer in the same group.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstTimeSeeing(String eventId, String eventType) {
        if (processed.existsById(eventId)) {
            return false;
        }
        processed.save(ProcessedEvent.builder().eventId(eventId).eventType(eventType).build());
        return true;
    }

    @Transactional(readOnly = true)
    public long totalReserved() {
        return stock.totalReserved();
    }
}
