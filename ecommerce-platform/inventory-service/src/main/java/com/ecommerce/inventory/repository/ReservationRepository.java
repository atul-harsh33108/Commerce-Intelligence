package com.ecommerce.inventory.repository;

import com.ecommerce.inventory.entity.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findByOrderKey(String orderKey);

    Optional<Reservation> findByOrderKeyAndProductKey(String orderKey, String productKey);

    List<Reservation> findByOrderKeyAndStatus(String orderKey, Reservation.Status status);

    /** Reservations that have sat in RESERVED too long - the sweeper's only query. */
    List<Reservation> findByStatusAndCreatedAtBefore(Reservation.Status status, Instant before);
}
