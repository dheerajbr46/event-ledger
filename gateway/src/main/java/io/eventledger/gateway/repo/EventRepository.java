package io.eventledger.gateway.repo;

import io.eventledger.gateway.domain.Event;
import io.eventledger.gateway.domain.EventStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, String> {

    List<Event> findByAccountId(String accountId, Sort sort);

    /** Events not yet applied to the account, oldest first — the replay job's work queue. */
    List<Event> findByStatusIn(Collection<EventStatus> statuses, Limit limit, Sort sort);
}
