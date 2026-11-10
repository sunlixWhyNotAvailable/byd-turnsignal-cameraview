package com.byd.extend;

import java.util.*;

/** Source-side continuity survives coalescing of intermediate UI deliveries. */
final class DistanceObjectContinuity {
    private Map<Integer, DistanceObject> previous = Collections.emptyMap();
    private long serial;

    List<DistanceObject> accept(List<DistanceObject> objects) {
        Set<Integer> seen = new HashSet<>(), duplicates = new HashSet<>();
        for (DistanceObject object : objects)
            if (object.id != null && !seen.add(object.id)) duplicates.add(object.id);
        Map<Integer, DistanceObject> next = new HashMap<>();
        List<DistanceObject> result = new ArrayList<>(objects.size());
        for (DistanceObject object : objects) {
            if (object.id == null || duplicates.contains(object.id)) {
                result.add(object.withContinuity(0));
                continue;
            }
            DistanceObject old = previous.get(object.id);
            DistanceObject tracked = object.withContinuity(old != null && old.type.equals(object.type)
                    ? old.continuity : ++serial);
            next.put(object.id, tracked); result.add(tracked);
        }
        previous = next;
        return result;
    }
    void clear() { previous = Collections.emptyMap(); }
}
