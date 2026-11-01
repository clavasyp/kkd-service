package ru.kkd.domain.org;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.kkd.domain.model.Unit;

class UnitTreeTest {

    // DEP -> N -> N-1 -> N-2 и DEP -> S. Дочерние стоят раньше родителей, чтобы порядок в списке не помогал.
    private static final UnitTree TREE = new UnitTree(List.of(
            unit("N-2", "N-1"),
            unit("S", "DEP"),
            unit("N-1", "N"),
            unit("N", "DEP"),
            unit("DEP", null)));

    @Test
    void rootBranchContainsAllUnits() {
        assertThat(TREE.branchOf("DEP")).containsExactlyInAnyOrder("DEP", "N", "S", "N-1", "N-2");
    }

    @Test
    void leafBranchContainsOnlyItself() {
        assertThat(TREE.branchOf("S")).containsExactly("S");
        assertThat(TREE.branchOf("N-2")).containsExactly("N-2");
    }

    @Test
    void branchGoesThroughThreeLevelsAndSkipsParentAndSibling() {
        assertThat(TREE.branchOf("N")).containsExactlyInAnyOrder("N", "N-1", "N-2");
    }

    @Test
    void branchHasNoDepthLimit() {
        List<Unit> chain = new ArrayList<>();
        chain.add(unit("U-0", null));
        for (int i = 1; i < 100; i++) {
            chain.add(unit("U-" + i, "U-" + (i - 1)));
        }

        assertThat(new UnitTree(chain).branchOf("U-0")).hasSize(100).contains("U-0", "U-50", "U-99");
    }

    @Test
    void unknownIdGivesSetOfItself() {
        assertThat(TREE.branchOf("NOPE")).containsExactly("NOPE");
        assertThat(new UnitTree(List.of()).branchOf("NOPE")).containsExactly("NOPE");
    }

    // Отдельный поток нужен тестам цикла: в обычном режиме JUnit только прерывает поток,
    // а бесконечный цикл на прерывание не отзовётся, и сборка зависнет вместо падения теста.

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void cycleOfTwoUnitsDoesNotHang() {
        UnitTree tree = new UnitTree(List.of(unit("A", "B"), unit("B", "A")));

        assertThat(tree.branchOf("A")).containsExactlyInAnyOrder("A", "B");
        assertThat(tree.branchOf("B")).containsExactlyInAnyOrder("A", "B");
    }

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void unitThatIsItsOwnParentDoesNotHang() {
        UnitTree tree = new UnitTree(List.of(unit("A", "A")));

        assertThat(tree.branchOf("A")).containsExactly("A");
    }

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void cycleOfThreeUnitsKeepsDescendantAndDoesNotHang() {
        // A, B и C замкнуты в цикл, а D это обычный лист под A.
        UnitTree tree = new UnitTree(List.of(unit("A", "C"), unit("B", "A"), unit("C", "B"), unit("D", "A")));

        assertThat(tree.branchOf("B")).containsExactlyInAnyOrder("A", "B", "C", "D");
        assertThat(tree.branchOf("D")).containsExactly("D");
    }

    private static Unit unit(String id, String parentId) {
        return new Unit(id, "Подразделение " + id, parentId);
    }
}
