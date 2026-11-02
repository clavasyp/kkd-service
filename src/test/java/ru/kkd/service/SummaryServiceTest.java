package ru.kkd.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static ru.kkd.domain.TestData.activeGoal;
import static ru.kkd.domain.TestData.noParentIssue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionDefinition;
import ru.kkd.domain.delta.DeltaCounts;
import ru.kkd.domain.model.GoalLevel;
import ru.kkd.domain.model.GoalRow;
import ru.kkd.domain.model.Issue;
import ru.kkd.domain.model.Run;
import ru.kkd.domain.model.RunStatus;
import ru.kkd.domain.model.Unit;
import ru.kkd.domain.org.UnitTree;
import ru.kkd.domain.summary.RunData;
import ru.kkd.domain.summary.Summary;
import ru.kkd.domain.summary.SummaryAssembler;
import ru.kkd.repository.IssueRepository;
import ru.kkd.repository.RunRepository;
import ru.kkd.repository.SnapshotRepository;
import ru.kkd.repository.UnitRepository;

class SummaryServiceTest {

    private static final LocalDate DATE_1 = LocalDate.of(2026, 10, 19);
    private static final LocalDate DATE_2 = LocalDate.of(2026, 10, 26);
    private static final LocalDate DATE_3 = LocalDate.of(2026, 11, 2);

    private static final Run FIRST = new Run("R1", DATE_1, RunStatus.COMPLETED, null, null);
    private static final Run SECOND = new Run("R2", DATE_2, RunStatus.COMPLETED, "R1", null);
    private static final Run FAILED = new Run("RF", DATE_2, RunStatus.FAILED, null, "сбой");
    private static final Run RUNNING = new Run("RR", DATE_3, RunStatus.RUNNING, null, null);

    private static final Unit ROOT = new Unit("ROOT", "Корень", null);
    private static final Unit CHILD = new Unit("CHILD", "Дочернее", "ROOT");

    private final RunRepository runs = mock(RunRepository.class);
    private final UnitRepository units = mock(UnitRepository.class);
    private final SnapshotRepository snapshots = mock(SnapshotRepository.class);
    private final IssueRepository issues = mock(IssueRepository.class);
    private final SummaryAssembler assembler = mock(SummaryAssembler.class);

    private final SummaryService service = new SummaryService(runs, units, snapshots, issues, assembler);

    @BeforeEach
    void givenCommonData() {
        when(runs.findById(any())).thenReturn(Optional.empty());
        when(units.findById(any())).thenReturn(Optional.empty());
        when(units.findById("ROOT")).thenReturn(Optional.of(ROOT));
        when(units.findById("CHILD")).thenReturn(Optional.of(CHILD));
        when(units.findAll()).thenReturn(List.of(ROOT, CHILD));
    }

    // 404

    @Test
    void unknownRunIs404() {
        KkdException error = failure("NOPE", "ROOT");

        assertThat(error.code()).isEqualTo(KkdException.RUN_NOT_FOUND);
        assertThat(error.getMessage()).isEqualTo("Прогон NOPE не найден");
        verifyNoDataRead();
    }

    @Test
    void unknownUnitIs404() {
        givenRun(FIRST);

        KkdException error = failure("R1", "NOPE");

        assertThat(error.code()).isEqualTo(KkdException.UNIT_NOT_FOUND);
        assertThat(error.getMessage()).isEqualTo("Подразделение NOPE не найдено");
        verifyNoDataRead();
    }

    // 409

    @Test
    void failedRunIs409() {
        givenRun(FAILED);

        KkdException error = failure("RF", "ROOT");

        assertThat(error.code()).isEqualTo(KkdException.RUN_FAILED);
        assertThat(error.getMessage()).isEqualTo("Прогон RF завершился с ошибкой");
        verifyNoDataRead();
    }

    @Test
    void runningRunIs409() {
        givenRun(RUNNING);

        KkdException error = failure("RR", "ROOT");

        assertThat(error.code()).isEqualTo(KkdException.RUN_IN_PROGRESS);
        assertThat(error.getMessage()).isEqualTo("Прогон RR ещё выполняется");
        verifyNoDataRead();
    }

    // Порядок раздела 8.1: прогон, подразделение, состояние

    @Test
    void runIsCheckedBeforeUnit() {
        assertThat(failure("NOPE", "NOPE").code()).isEqualTo(KkdException.RUN_NOT_FOUND);
    }

    @Test
    void unitIsCheckedBeforeStateOfFailedRun() {
        givenRun(FAILED);

        assertThat(failure("RF", "NOPE").code()).isEqualTo(KkdException.UNIT_NOT_FOUND);
    }

    @Test
    void unitIsCheckedBeforeStateOfRunningRun() {
        givenRun(RUNNING);

        assertThat(failure("RR", "NOPE").code()).isEqualTo(KkdException.UNIT_NOT_FOUND);
    }

    // База сравнения: только compared_to_run_id

    @Test
    void withoutComparedToRunIdBaseIsNull() {
        givenRun(FIRST);
        givenRunData("R1", List.of());
        Summary expected = summaryOf(FIRST, null);
        when(assembler.assemble(any(), isNull(), any(), any())).thenReturn(expected);

        Summary summary = service.getSummary(new SummaryQuery("R1", "ROOT"));

        assertThat(summary).isSameAs(expected);
        verify(assembler).assemble(any(), isNull(), any(), any());
    }

    @Test
    void withoutComparedToRunIdOtherRunsAreNotRead() {
        givenRun(FIRST);
        givenRunData("R1", List.of());

        service.getSummary(new SummaryQuery("R1", "ROOT"));

        verify(runs, never()).findAll();
        verify(runs, never()).findAllWithIssueCounts();
        verify(runs).findById("R1");
        verify(snapshots).loadGoals("R1");
    }

    @Test
    void baseIsTakenFromComparedToRunIdWithItsOwnData() {
        givenRun(FIRST);
        givenRun(SECOND);
        List<GoalRow> firstGoals = givenRunData("R1", List.of(noParentIssue("G-1", "ROOT")));
        List<GoalRow> secondGoals = givenRunData("R2", List.of(noParentIssue("G-2", "ROOT")));

        service.getSummary(new SummaryQuery("R2", "ROOT"));

        ArgumentCaptor<RunData> current = ArgumentCaptor.forClass(RunData.class);
        ArgumentCaptor<RunData> base = ArgumentCaptor.forClass(RunData.class);
        verify(assembler).assemble(current.capture(), base.capture(), any(), any());
        assertThat(current.getValue()).isEqualTo(new RunData(SECOND, secondGoals, List.of(noParentIssue("G-2", "ROOT"))));
        assertThat(base.getValue()).isEqualTo(new RunData(FIRST, firstGoals, List.of(noParentIssue("G-1", "ROOT"))));
    }

    @Test
    void baseIsNotChosenAgainEvenIfAnotherCompletedRunIsCloser() {
        // Прогон R1b появился позже и ближе по дате. API не выбирает базу заново: раздел 6.4.
        Run closer = new Run("R1b", DATE_2.minusDays(1), RunStatus.COMPLETED, "R1", null);
        givenRun(FIRST);
        givenRun(closer);
        givenRun(SECOND);
        when(runs.findAll()).thenReturn(List.of(FIRST, closer, SECOND));
        givenRunData("R1", List.of());
        givenRunData("R2", List.of());

        service.getSummary(new SummaryQuery("R2", "ROOT"));

        ArgumentCaptor<RunData> base = ArgumentCaptor.forClass(RunData.class);
        verify(assembler).assemble(any(), base.capture(), any(), any());
        assertThat(base.getValue().run()).isEqualTo(FIRST);
        verify(snapshots, never()).loadGoals("R1b");
    }

    @Test
    void goalsOfBothRunsAreReadAndKeyResultsAreNot() {
        givenRun(FIRST);
        givenRun(SECOND);
        givenRunData("R1", List.of());
        givenRunData("R2", List.of());

        service.getSummary(new SummaryQuery("R2", "ROOT"));

        verify(snapshots).loadGoals("R2");
        verify(snapshots).loadGoals("R1");
        verify(snapshots, never()).load(any(), any());
    }

    // Подразделение и дерево

    @Test
    void unitAndTreeComeFromRepository() {
        givenRun(FIRST);
        givenRunData("R1", List.of());

        service.getSummary(new SummaryQuery("R1", "ROOT"));

        ArgumentCaptor<UnitTree> tree = ArgumentCaptor.forClass(UnitTree.class);
        verify(assembler).assemble(any(), isNull(), eq(ROOT), tree.capture());
        assertThat(tree.getValue().branchOf("ROOT")).containsExactlyInAnyOrder("ROOT", "CHILD");
    }

    // Настоящая сборка: сервис передаёт в неё данные обоих прогонов

    @Test
    void realAssemblerGetsDataOfBothRuns() {
        SummaryService withRealAssembler = new SummaryService(runs, units, snapshots, issues, new SummaryAssembler());
        givenRun(FIRST);
        givenRun(SECOND);
        givenRunData("R1", List.of(noParentIssue("G-1", "ROOT")));
        givenRunData("R2", List.of(noParentIssue("G-2", "CHILD"), noParentIssue("G-3", "CHILD")));

        Summary summary = withRealAssembler.getSummary(new SummaryQuery("R2", "ROOT"));

        assertThat(summary.run()).isEqualTo(SECOND);
        assertThat(summary.comparedTo()).isEqualTo(FIRST);
        assertThat(summary.unit()).isEqualTo(ROOT);
        assertThat(summary.delta()).isEqualTo(new DeltaCounts(2, 0, 1));
        assertThat(summary.metrics().get(1).value()).isEqualByComparingTo(new BigDecimal("2"));
        assertThat(summary.metrics().get(1).previous()).isEqualByComparingTo(new BigDecimal("1"));
    }

    // Только чтение

    @Test
    void nothingIsWritten() {
        givenRun(FIRST);
        givenRun(SECOND);
        givenRunData("R1", List.of());
        givenRunData("R2", List.of());

        service.getSummary(new SummaryQuery("R2", "ROOT"));

        verifyNothingWritten();
    }

    @Test
    void readsInOneReadOnlyRepeatableReadTransactionOpenedByProxy() {
        givenRun(FIRST);
        givenRunData("R1", List.of());

        TransactionDefinition transaction = TransactionProbe.definitionOfCall(
                SummaryService.class, service, proxy -> proxy.getSummary(new SummaryQuery("R1", "ROOT")));

        assertThat(transaction.isReadOnly()).isTrue();
        assertThat(transaction.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    private void givenRun(Run run) {
        when(runs.findById(run.id())).thenReturn(Optional.of(run));
    }

    /** Сводке нужны только цели снимка: КР не читаются. */
    private List<GoalRow> givenRunData(String runId, List<Issue> runIssues) {
        List<GoalRow> goals = List.of(activeGoal("G-" + runId, "ROOT", GoalLevel.DEPARTMENT, null));
        when(snapshots.loadGoals(runId)).thenReturn(goals);
        when(issues.findByRun(runId)).thenReturn(runIssues);
        return goals;
    }

    private KkdException failure(String runId, String unitId) {
        KkdException error = catchThrowableOfType(
                KkdException.class, () -> service.getSummary(new SummaryQuery(runId, unitId)));
        assertThat(error).as("ожидалась KkdException").isNotNull();
        return error;
    }

    private void verifyNoDataRead() {
        verifyNoInteractions(snapshots, issues, assembler);
    }

    private void verifyNothingWritten() {
        verify(runs, never()).markCompleted(any(), any());
        verify(runs, never()).markFailed(any(), any());
        verify(issues, never()).saveAll(any(), anyList());
        verify(snapshots, never()).freeze(any());
    }

    private static Summary summaryOf(Run run, Run comparedTo) {
        return new Summary(run, comparedTo, ROOT, List.of(), List.of(), new DeltaCounts(null, null, null));
    }
}
