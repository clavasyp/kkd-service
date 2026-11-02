package ru.kkd.domain.delta;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.kkd.domain.model.Run;
import ru.kkd.domain.model.RunStatus;

class BaseRunSelectorTest {

    private static final Run CURRENT = run("RUN-5", "2026-10-26", RunStatus.RUNNING);

    private final BaseRunSelector selector = new BaseRunSelector();

    @Test
    void picksLatestCompletedRunRegardlessOfListOrder() {
        Run first = completed("RUN-1", "2026-10-05");
        Run second = completed("RUN-2", "2026-10-12");
        Run third = completed("RUN-3", "2026-10-19");

        assertThat(selector.select(CURRENT, List.of(second, third, first))).contains(third);
    }

    @Test
    void failedAndRunningRunsAreSkipped() {
        Run completed = completed("RUN-1", "2026-10-05");
        Run failed = run("RUN-2", "2026-10-12", RunStatus.FAILED);
        Run running = run("RUN-3", "2026-10-19", RunStatus.RUNNING);

        assertThat(selector.select(CURRENT, List.of(completed, failed, running))).contains(completed);
    }

    @Test
    void noEarlierRunsGiveEmpty() {
        Run later = completed("RUN-9", "2026-11-02");

        assertThat(selector.select(CURRENT, List.of())).isEmpty();
        assertThat(selector.select(CURRENT, List.of(CURRENT))).isEmpty();
        assertThat(selector.select(CURRENT, List.of(CURRENT, later))).isEmpty();
    }

    @Test
    void allEarlierRunsFailedGiveEmpty() {
        Run failedFirst = run("RUN-1", "2026-10-12", RunStatus.FAILED);
        Run failedSecond = run("RUN-2", "2026-10-19", RunStatus.FAILED);

        assertThat(selector.select(CURRENT, List.of(failedFirst, failedSecond, CURRENT))).isEmpty();
    }

    @Test
    void earlierRunThatIsStillRunningGivesEmpty() {
        Run running = run("RUN-1", "2026-10-19", RunStatus.RUNNING);

        assertThat(selector.select(CURRENT, List.of(running, CURRENT))).isEmpty();
    }

    @Test
    void laterCompletedRunIsNotPicked() {
        Run earlier = completed("RUN-3", "2026-10-19");
        Run later = completed("RUN-9", "2026-11-02");

        assertThat(selector.select(CURRENT, List.of(later, earlier))).contains(earlier);
    }

    @Test
    void onSameDateSmallerIdIsEarlierAndGreaterIdIsLater() {
        Run smallerId = completed("RUN-4", "2026-10-26");
        Run greaterId = completed("RUN-6", "2026-10-26");

        assertThat(selector.select(CURRENT, List.of(greaterId, smallerId))).contains(smallerId);
        assertThat(selector.select(CURRENT, List.of(greaterId))).isEmpty();
    }

    @Test
    void dateIsComparedBeforeId() {
        Run earlierWithGreaterId = completed("RUN-9", "2026-10-19");
        Run laterWithSmallerId = completed("RUN-1", "2026-11-02");

        assertThat(selector.select(CURRENT, List.of(laterWithSmallerId, earlierWithGreaterId)))
                .contains(earlierWithGreaterId);
    }

    @Test
    void amongCandidatesLatestDateThenGreatestIdWins() {
        Run oldDateGreatestId = completed("RUN-Z", "2026-10-12");
        Run latestDateSmallerId = completed("RUN-A", "2026-10-19");
        Run latestDateGreaterId = completed("RUN-B", "2026-10-19");

        assertThat(selector.select(CURRENT, List.of(latestDateGreaterId, oldDateGreatestId, latestDateSmallerId)))
                .contains(latestDateGreaterId);
    }

    // U+FF21 в UTF-16 это 0xFF21, а U+1F600 это пара 0xD83D 0xDE00: String.compareTo ставит смайлик раньше.
    // COLLATE "C" сравнивает байты UTF-8, EF BC A1 против F0 9F 98 80, и ставит его позже. Так же идут такт и /api/runs.
    private static final String FULLWIDTH_A = "RUN-" + Character.toString(0xFF21);
    private static final String EMOJI = "RUN-" + Character.toString(0x1F600);

    @Test
    void onSameDateIdsAreComparedLikeCollateCEvenOutsideBmp() {
        Run fullwidthA = completed(FULLWIDTH_A, "2026-11-02");
        Run emojiRunning = run(EMOJI, "2026-11-02", RunStatus.RUNNING);
        Run emoji = completed(EMOJI, "2026-11-02");
        Run fullwidthARunning = run(FULLWIDTH_A, "2026-11-02", RunStatus.RUNNING);

        assertThat(selector.select(emojiRunning, List.of(fullwidthA, emojiRunning))).contains(fullwidthA);
        assertThat(selector.select(fullwidthARunning, List.of(emoji, fullwidthARunning))).isEmpty();
    }

    @Test
    void laterRunIsComparedWithRunThatIsLastInOrderOfDatabase() {
        Run fullwidthA = completed(FULLWIDTH_A, "2026-11-02");
        Run emoji = completed(EMOJI, "2026-11-02");
        Run later = run("RUN-9", "2026-11-09", RunStatus.RUNNING);

        assertThat(selector.select(later, List.of(emoji, fullwidthA, later))).contains(emoji);
    }

    @Test
    void currentRunIsNotPickedEvenIfCompleted() {
        Run earlier = completed("RUN-1", "2026-10-19");
        Run current = completed("RUN-2", "2026-10-26");

        assertThat(selector.select(current, List.of(current, earlier))).contains(earlier);
        assertThat(selector.select(current, List.of(current))).isEmpty();
    }

    private static Run completed(String id, String snapshotDate) {
        return run(id, snapshotDate, RunStatus.COMPLETED);
    }

    private static Run run(String id, String snapshotDate, RunStatus status) {
        return new Run(id, LocalDate.parse(snapshotDate), status, null, null);
    }
}
