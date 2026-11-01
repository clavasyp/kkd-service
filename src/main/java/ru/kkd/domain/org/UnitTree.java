package ru.kkd.domain.org;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.kkd.domain.model.Unit;

public class UnitTree {

    private final Map<String, List<String>> childrenByParent = new HashMap<>();

    public UnitTree(List<Unit> units) {
        for (Unit unit : units) {
            if (unit.parentId() != null) {
                childrenByParent.computeIfAbsent(unit.parentId(), parent -> new ArrayList<>()).add(unit.id());
            }
        }
    }

    /** Подразделение и все его потомки на любую глубину. */
    public Set<String> branchOf(String unitId) {
        Set<String> branch = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(unitId);
        while (!queue.isEmpty()) {
            String current = queue.remove();
            // Уже посещённое не обходим второй раз: так цикл в данных не приводит к зависанию.
            if (branch.add(current)) {
                queue.addAll(childrenByParent.getOrDefault(current, List.of()));
            }
        }
        return branch;
    }
}
