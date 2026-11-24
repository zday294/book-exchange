package com.bookexchange.party;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * The party as everyone sees it (the same for every viewer). Names and order only: nothing about any book.
 */
@Schema(description = "The party queue. Names, order and who is online; nothing about any book.")
public record PartyState(
        @Schema(description = "True while a party is running") boolean active,
        @Schema(description = "Why the party ended, on the update that ends it (otherwise absent)", nullable = true)
        String endedReason,
        @Schema(description = "Whose turn it is right now, if anyone's", example = "Alex", nullable = true) String current,
        @Schema(description = "Seconds left of the current turn, counted by the server's clock", example = "87", nullable = true)
        Integer turnSecondsLeft,
        @Schema(description = "People who had their turn, then the current person, then those waiting in order")
        List<Entry> queue,
        @Schema(description = "When no party is running: the people online right now who could be put in the queue "
                + "(those who haven't claimed a book yet)") List<String> eligible) {

    @Schema(description = "One person in the queue")
    public record Entry(
            @Schema(example = "Alex") String name,
            @Schema(description = "DONE: had their turn and claimed a book. CURRENT: it is their turn. "
                    + "WAITING: still in line.") String status,
            @Schema(description = "Whether they have the exchange page open (with a short grace period)") boolean online) {}

    public static PartyState inactive(String endedReason, List<String> eligible) {
        return new PartyState(false, endedReason, null, null, List.of(), eligible);
    }
}
