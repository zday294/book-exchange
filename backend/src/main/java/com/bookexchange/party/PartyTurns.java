package com.bookexchange.party;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The party queue in the database ({@code party_turn}). Written as plain SQL on purpose: every comparison of a
 * turn's end time uses the database's clock ({@code now()}), the same clock that set it, so the result never
 * depends on this server's clock, and a conditional UPDATE is what makes "exactly one winner" true.
 */
@Repository
public class PartyTurns {

    public static final String WAITING = "WAITING";
    public static final String CURRENT = "CURRENT";
    public static final String DONE = "DONE";

    /** One row of the queue. {@code secondsLeft} and {@code expired} are only meaningful for the CURRENT row. */
    public record Turn(long id, String name, String status, int secondsLeft, boolean expired) {}

    private final JdbcTemplate jdbc;

    public PartyTurns(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void deleteAll(long exchangeId) {
        jdbc.update("delete from party_turn where exchange_id = ?", exchangeId);
    }

    /** Puts a person at the end of the line. */
    public void addToEnd(long exchangeId, String name) {
        jdbc.update("insert into party_turn (exchange_id, participant_name, position, status) values (?, ?, "
                + "(select coalesce(max(position), 0) + 1 from party_turn where exchange_id = ?), 'WAITING')",
                exchangeId, name, exchangeId);
    }

    /** True if the person is anywhere in the queue (waiting, current or done), whatever the capitalization. */
    public boolean contains(long exchangeId, String name) {
        Integer n = jdbc.queryForObject("select count(*) from party_turn where exchange_id = ? "
                + "and lower(participant_name) = lower(?)", Integer.class, exchangeId, name);
        return n != null && n > 0;
    }

    /** The person whose turn it is, with the time left by the database's clock. */
    public Optional<Turn> current(long exchangeId) {
        return jdbc.query("select id, participant_name, status, "
                        + "greatest(0, ceil(extract(epoch from (turn_ends_at - now()))))::int as left, "
                        + "turn_ends_at <= now() as expired "
                        + "from party_turn where exchange_id = ? and status = 'CURRENT'",
                (rs, i) -> new Turn(rs.getLong("id"), rs.getString("participant_name"), rs.getString("status"),
                        rs.getInt("left"), rs.getBoolean("expired")), exchangeId).stream().findFirst();
    }

    /** Everyone in the queue in display order: done first, then the current person, then those waiting in line. */
    public List<Turn> list(long exchangeId) {
        return jdbc.query("select id, participant_name, status, "
                        + "case when status = 'CURRENT' then greatest(0, ceil(extract(epoch from (turn_ends_at - now()))))::int else 0 end as left, "
                        + "coalesce(turn_ends_at <= now(), false) as expired "
                        + "from party_turn where exchange_id = ? "
                        + "order by case status when 'DONE' then 0 when 'CURRENT' then 1 else 2 end, "
                        + "case when status = 'DONE' then turn_started_at end, position, id",
                (rs, i) -> new Turn(rs.getLong("id"), rs.getString("participant_name"), rs.getString("status"),
                        rs.getInt("left"), rs.getBoolean("expired")), exchangeId);
    }

    /** How many people are still in line or have the turn (not counting those who are done). */
    public int activeCount(long exchangeId) {
        Integer n = jdbc.queryForObject("select count(*) from party_turn where exchange_id = ? "
                + "and status in ('WAITING', 'CURRENT')", Integer.class, exchangeId);
        return n == null ? 0 : n;
    }

    /** Gives the turn to the next person in line, with a fresh timer. Returns false when nobody is waiting. */
    public boolean promoteNext(long exchangeId, int turnSeconds) {
        return jdbc.update("update party_turn set status = 'CURRENT', turn_started_at = now(), "
                + "turn_ends_at = now() + make_interval(secs => ?) "
                + "where id = (select id from party_turn where exchange_id = ? and status = 'WAITING' "
                + "order by position, id limit 1)", turnSeconds, exchangeId) == 1;
    }

    /**
     * The current person claimed a book: marks their turn done, but only if it IS their turn and the time hasn't
     * run out. Returns the number of rows changed (1 or 0); under concurrency exactly one caller gets 1.
     */
    public int completeCurrent(long exchangeId, String name) {
        return jdbc.update("update party_turn set status = 'DONE' where exchange_id = ? and status = 'CURRENT' "
                + "and lower(participant_name) = lower(?) and turn_ends_at > now()", exchangeId, name);
    }

    /** The current person's turn ended without a claim: they go to the end of the line (they are not removed). */
    public boolean moveCurrentToEnd(long exchangeId) {
        return jdbc.update("update party_turn set status = 'WAITING', turn_started_at = null, turn_ends_at = null, "
                + "position = (select coalesce(max(position), 0) + 1 from party_turn where exchange_id = ?) "
                + "where exchange_id = ? and status = 'CURRENT'", exchangeId, exchangeId) == 1;
    }

    // ---- turns in a row that ended without a claim ----

    public int idleTurns(long exchangeId) {
        Integer n = jdbc.queryForObject("select party_idle_turns from exchange where id = ?", Integer.class, exchangeId);
        return n == null ? 0 : n;
    }

    public void resetIdleTurns(long exchangeId) {
        jdbc.update("update exchange set party_idle_turns = 0 where id = ?", exchangeId);
    }

    public int addIdleTurn(long exchangeId) {
        Integer n = jdbc.queryForObject("update exchange set party_idle_turns = party_idle_turns + 1 where id = ? "
                + "returning party_idle_turns", Integer.class, exchangeId);
        return n == null ? 0 : n;
    }
}
