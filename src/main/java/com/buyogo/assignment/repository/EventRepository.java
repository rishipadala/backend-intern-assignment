package com.buyogo.assignment.repository;

import com.buyogo.assignment.entity.MachineEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface EventRepository extends JpaRepository<MachineEvent, Long> {

    Optional<MachineEvent> findByEventId(String eventId);

    // --- NEW OPTIMIZATION: Fetch multiple IDs in one go ---
    List<MachineEvent> findByEventIdIn(List<String> eventIds);

    @Query("SELECT e FROM MachineEvent e WHERE e.machineId = :machineId AND e.eventTime >= :start AND e.eventTime < :end")
    List<MachineEvent> findEventsForStats(String machineId, Instant start, Instant end);

    @Query("SELECT e.machineId, COUNT(e), SUM(CASE WHEN e.defectCount = -1 THEN 0 ELSE e.defectCount END) " +
            "FROM MachineEvent e " +
            "WHERE e.eventTime >= :start AND e.eventTime < :end " +
            "GROUP BY e.machineId " +
            "ORDER BY SUM(CASE WHEN e.defectCount = -1 THEN 0 ELSE e.defectCount END) DESC")
    List<Object[]> findTopDefects(Instant start, Instant end);
}