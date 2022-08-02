/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.admin.indices.rollover;

import org.elasticsearch.core.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class RolloverConditionEvaluation {

    private final Map<String, Boolean> results;
    private final boolean areConditionsMet;
    private final List<Condition<?>> metConditions;

    private static void validateConditions(final Collection<Condition<?>> conditions) {
        if (conditions == null) {
            throw new IllegalArgumentException("conditions must not be null");
        }

        for (Condition<?> c : conditions) {
            if (c.type() == null) {
                throw new IllegalArgumentException("condition " + c + " has null type");
            }
        }

        if (conditions.size() > 0 && conditions.stream().allMatch(c -> Condition.Type.MIN == c.type())) {
            throw new IllegalArgumentException("At least one max_* rollover condition must be set.");
        }
    }

    public static boolean areConditionsMet(final Collection<Condition<?>> conditions, Map<String, Boolean> evaluationResults) {
        validateConditions(conditions);

        // validate that the conditions and evaluation results have parity
        Set<String> conditionKeys = conditions.stream().map(c -> c.toString()).collect(Collectors.toSet());
        if (evaluationResults.keySet().equals(conditionKeys) == false) {
            throw new IllegalArgumentException(
                "Mismatch between conditions " + conditionKeys + " and evaluationResults " + evaluationResults.keySet()
            );
        }

        // sanity check -- the logic below applies as long as there are only min/max conditions -- if we add more conditions types,
        // then this logic will need to be updated
        assert Condition.Type.values().length == 2;

        if (conditions.size() == 0) {
            // if there are no conditions, then the evaluation is trivially true
            return true;
        } else {
            boolean allMinConditionsMet = conditions.stream()
                .filter(c -> Condition.Type.MIN == c.type())
                .allMatch(c -> evaluationResults.get(c.toString()));

            boolean anyMaxConditionsMet = conditions.stream()
                .filter(c -> Condition.Type.MAX == c.type())
                .anyMatch(c -> evaluationResults.get(c.toString()));

            return allMinConditionsMet && anyMaxConditionsMet;
        }
    }

    public RolloverConditionEvaluation(final Collection<Condition<?>> conditions, @Nullable final Condition.Stats stats) {
        validateConditions(conditions);

        if (conditions.size() == 0) {
            // if there are no conditions, then the evaluation is trivially true
            results = Map.of();
            areConditionsMet = true;
            metConditions = List.of();
        } else if (stats == null) {
            // else if there are no stats, then the evaluation is trivially false (and all the condition results are false)
            results = conditions.stream().collect(Collectors.toMap(c -> c.toString(), c -> false));
            areConditionsMet = false;
            metConditions = List.of();
        } else {
            Map<Condition<?>, Boolean> evaluationResults = conditions.stream()
                .map(condition -> condition.evaluate(stats))
                .collect(Collectors.toMap(Condition.Result::condition, Condition.Result::matched));

            results = evaluationResults.entrySet().stream().collect(Collectors.toMap(e -> e.getKey().toString(), e -> e.getValue()));

            areConditionsMet = areConditionsMet(conditions, results);

            if (areConditionsMet) {
                metConditions = evaluationResults.entrySet()
                    .stream()
                    .filter(Map.Entry::getValue)
                    .map(entry -> ((Condition<?>) entry.getKey()))
                    .collect(Collectors.toList());
            } else {
                metConditions = List.of();
            }
        }
    }

    public Map<String, Boolean> getResults() {
        return results;
    }

    public boolean areConditionsMet() {
        return areConditionsMet;
    }

    public List<Condition<?>> getMetConditions() {
        return metConditions;
    }
}
