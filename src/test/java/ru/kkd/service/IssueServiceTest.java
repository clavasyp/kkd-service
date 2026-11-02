package ru.kkd.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static ru.kkd.domain.TestData.noKrIssue;
import static ru.kkd.domain.TestData.staleIssue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionDefinition;
import ru.kkd.domain.model.Issue;
import ru.kkd.domain.model.RuleCode;
import ru.kkd.domain.model.Run;
import ru.kkd.domain.model.RunStatus;
import ru.kkd.domain.model.Severity;
import ru.kkd.domain.model.Unit;
import ru.kkd.repository.IssueFilter;
import ru.kkd.repository.IssueRepository;
import ru.kkd.repository.RunRepository;
import ru.kkd.repository.SnapshotRepository;
import ru.kkd.repository.UnitRepository;

class IssueServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 26);

    private static final Run COMPLETED = new Run("R1", DATE, RunStatus.COMPLETED, null, null);
    private static final Run FAILED = new Run("RF", DATE, RunStatus.FAILED, null, "сбой");
    private static final Run RUNNING = new Run("RR", DATE, RunStatus.RUNNING, null, null);

    private static final Unit ROOT = new Unit("ROOT", "Корень", null);
    private static final Unit CHILD = new Unit("CHILD", "Дочернее", "ROOT");
    private static final Unit GRANDCHILD = new Unit("GRANDCHILD", "Внучатое", "CHILD");
    private static final Unit OTHER = new Unit("OTHER", "Соседнее", "ROOT");

    private final RunRepository runs = mock(RunRepository.class);
    private final UnitRepository units = mock(UnitRepository.class);
    private final SnapshotRepository snapshots = mock(SnapshotRepository.class);
    private final IssueRepository issues = mock(IssueRepository.class);

    private final IssueService service = new IssueService(runs, units, snapshots, issues);

    @BeforeEach
    void givenCommonData() {
        when(runs.findById(any())).thenReturn(Optional.empty());
        when(runs.findById("R1")).thenReturn(Optional.of(COMPLETED));
        when(runs.findById("RF")).thenReturn(Optional.of(FAILED));
        when(runs.findById("RR")).thenReturn(Optional.of(RUNNING));
        when(units.findById(any())).thenReturn(Optional.empty());
        for (Unit unit : List.of(ROOT, CHILD, GRANDCHILD, OTHER)) {
            when(units.findById(unit.id())).thenReturn(Optional.of(unit));
        }
        when(units.findAll()).thenReturn(List.of(ROOT, CHILD, GRANDCHILD, OTHER));
        when(snapshots.findGoalTitles(any(), any())).thenReturn(Map.of());
        when(issues.findPage(any(), anyInt(), anyInt())).thenReturn(List.of());
    }

    // 404

    @Test
    void unknownRunIs404() {
        KkdException error = failure(query("NOPE", null));

        assertThat(error.code()).isEqualTo(KkdException.RUN_NOT_FOUND);
        assertThat(error.getMessage()).isEqualTo("Прогон NOPE не найден");
        verifyNoInteractions(snapshots, issues);
    }

    @Test
    void unknownUnitIs404() {
        KkdException error = failure(query("R1", "NOPE"));

        assertThat(error.code()).isEqualTo(KkdException.UNIT_NOT_FOUND);
        assertThat(error.getMessage()).isEqualTo("Подразделение NOPE не найдено");
        verifyNoInteractions(snapshots, issues);
    }

    // 409

    @Test
    void failedRunIs409() {
        KkdException error = failure(query("RF", null));

        assertThat(error.code()).isEqualTo(KkdException.RUN_FAILED);
        assertThat(error.getMessage()).isEqualTo("Прогон RF завершился с ошибкой");
        verifyNoInteractions(snapshots, issues);
    }

    @Test
    void runningRunIs409() {
        KkdException error = failure(query("RR", null));

        assertThat(error.code()).isEqualTo(KkdException.RUN_IN_PROGRESS);
        assertThat(error.getMessage()).isEqualTo("Прогон RR ещё выполняется");
        verifyNoInteractions(snapshots, issues);
    }

    // Порядок раздела 8.1: прогон, подразделение, состояние

    @Test
    void runIsCheckedBeforeUnit() {
        assertThat(failure(query("NOPE", "NOPE")).code()).isEqualTo(KkdException.RUN_NOT_FOUND);
    }

    @Test
    void unitIsCheckedBeforeStateOfFailedRun() {
        assertThat(failure(query("RF", "NOPE")).code()).isEqualTo(KkdException.UNIT_NOT_FOUND);
    }

    @Test
    void unitIsCheckedBeforeStateOfRunningRun() {
        assertThat(failure(query("RR", "NOPE")).code()).isEqualTo(KkdException.UNIT_NOT_FOUND);
    }

    @Test
    void existingUnitDoesNotHideStateOfRun() {
        assertThat(failure(query("RF", "ROOT")).code()).isEqualTo(KkdException.RUN_FAILED);
    }

    // Фильтр

    @Test
    void withoutUnitFilterHasNullUnitIdsAndUnitsAreNotRead() {
        service.findIssues(new IssueQuery("R1", null, null, null, 20, 0));

        IssueFilter filter = pageFilter();
        assertThat(filter).isEqualTo(new IssueFilter("R1", null, null, null));
        verifyNoInteractions(units);
    }

    @Test
    void filterIncludesUnitAndAllItsDescendants() {
        service.findIssues(query("R1", "ROOT"));

        assertThat(pageFilter().unitIds()).containsExactlyInAnyOrder("ROOT", "CHILD", "GRANDCHILD", "OTHER");
    }

    @Test
    void filterOfBranchDoesNotIncludeNeighboursAndParent() {
        service.findIssues(query("R1", "CHILD"));

        assertThat(pageFilter().unitIds()).isEqualTo(Set.of("CHILD", "GRANDCHILD"));
    }

    @Test
    void filterOfLeafIsItself() {
        service.findIssues(query("R1", "GRANDCHILD"));

        assertThat(pageFilter().unitIds()).isEqualTo(Set.of("GRANDCHILD"));
    }

    @Test
    void ruleCodeAndSeverityGoToFilter() {
        service.findIssues(new IssueQuery("R1", null, RuleCode.GOAL_NO_KR, Severity.CRITICAL, 20, 0));

        assertThat(pageFilter()).isEqualTo(new IssueFilter("R1", null, RuleCode.GOAL_NO_KR, Severity.CRITICAL));
    }

    @Test
    void pageAndCountUseTheSameFilter() {
        when(issues.count(any())).thenReturn(5);

        service.findIssues(new IssueQuery("R1", "CHILD", RuleCode.KR_STALE_PROGRESS, Severity.MINOR, 3, 2));

        IssueFilter expected = new IssueFilter(
                "R1", Set.of("CHILD", "GRANDCHILD"), RuleCode.KR_STALE_PROGRESS, Severity.MINOR);
        verify(issues).findPage(expected, 3, 2);
        verify(issues).count(expected);
    }

    // Страница

    @Test
    void itemsGetGoalTitleOfSnapshotAndPageKeepsParameters() {
        Issue stale = staleIssue("KR-1", "G-1", "ROOT");
        Issue noKr = noKrIssue("G-2", "CHILD");
        when(issues.findPage(any(), anyInt(), anyInt())).thenReturn(List.of(noKr, stale));
        when(issues.count(any())).thenReturn(7);
        when(snapshots.findGoalTitles("R1", Set.of("G-1", "G-2"))).thenReturn(Map.of("G-1", "Первая", "G-2", "Вторая"));

        IssuePage page = service.findIssues(new IssueQuery("R1", null, null, null, 2, 4));

        assertThat(page).isEqualTo(new IssuePage(
                "R1",
                List.of(new IssuePage.Item(noKr, "Вторая"), new IssuePage.Item(stale, "Первая")),
                7,
                2,
                4));
    }

    @Test
    void titlesAreReadOnlyForGoalsOfThePage() {
        // Две проблемы одной цели: название нужно один раз, а цели вне страницы не читаются вовсе.
        when(issues.findPage(any(), anyInt(), anyInt())).thenReturn(List.of(
                noKrIssue("G-2", "CHILD"), staleIssue("KR-2", "G-2", "CHILD"), staleIssue("KR-5", "G-5", "ROOT")));

        service.findIssues(new IssueQuery("R1", null, null, null, 3, 0));

        verify(snapshots).findGoalTitles("R1", Set.of("G-2", "G-5"));
    }

    @Test
    void offsetBehindEndGivesEmptyItemsAndKeepsTotal() {
        when(issues.count(any())).thenReturn(3);

        IssuePage page = service.findIssues(new IssueQuery("R1", null, null, null, 5, 1000));

        assertThat(page).isEqualTo(new IssuePage("R1", List.of(), 3, 5, 1000));
    }

    @Test
    void codeOfDisabledRuleGivesEmptyPage() {
        when(issues.count(any())).thenReturn(0);

        IssuePage page = service.findIssues(new IssueQuery("R1", null, RuleCode.GOAL_NO_OWNER, null, 20, 0));

        assertThat(page).isEqualTo(new IssuePage("R1", List.of(), 0, 20, 0));
    }

    @Test
    void unitWithoutIssuesGivesEmptyPage() {
        when(issues.count(any())).thenReturn(0);

        IssuePage page = service.findIssues(query("R1", "GRANDCHILD"));

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
    }

    // Только чтение

    @Test
    void nothingIsWritten() {
        service.findIssues(query("R1", "ROOT"));

        verify(runs, never()).markCompleted(any(), any());
        verify(runs, never()).markFailed(any(), any());
        verify(issues, never()).saveAll(any(), anyList());
        verify(snapshots, never()).freeze(any());
    }

    @Test
    void readsInOneReadOnlyRepeatableReadTransactionOpenedByProxy() {
        TransactionDefinition transaction = TransactionProbe.definitionOfCall(
                IssueService.class, service, proxy -> proxy.findIssues(query("R1", "ROOT")));

        assertThat(transaction.isReadOnly()).isTrue();
        assertThat(transaction.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    private static IssueQuery query(String runId, String unitId) {
        return new IssueQuery(runId, unitId, null, null, 20, 0);
    }

    private IssueFilter pageFilter() {
        ArgumentCaptor<IssueFilter> filter = ArgumentCaptor.forClass(IssueFilter.class);
        verify(issues).findPage(filter.capture(), anyInt(), anyInt());
        return filter.getValue();
    }

    private KkdException failure(IssueQuery query) {
        KkdException error = catchThrowableOfType(KkdException.class, () -> service.findIssues(query));
        assertThat(error).as("ожидалась KkdException").isNotNull();
        return error;
    }
}
