// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.

package com.starrocks.sql.optimizer.rule.transformation;

import com.google.common.collect.Lists;
import com.starrocks.catalog.FunctionSet;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.OptimizerContext;
import com.starrocks.sql.optimizer.Utils;
import com.starrocks.sql.optimizer.operator.Operator;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.SortPhase;
import com.starrocks.sql.optimizer.operator.logical.LogicalFilterOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalTopNOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalWindowOperator;
import com.starrocks.sql.optimizer.operator.pattern.Pattern;
import com.starrocks.sql.optimizer.operator.scalar.BinaryPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.CallOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.ConstantOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.rule.RuleType;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * For rank window functions, such as row_number, rank, dense_rank, if there exists rank related predicate
 * then we can add a TopN to filter data in order to reduce the amount of data to be exchanged and sorted
 * E.g.
 * select * from (
 * select *, rank() over (order by v2) as rk from t0
 * ) sub_t0
 * where rk < 4;
 */
public class PushDownPredicateWindowRankRule extends TransformationRule {

    public PushDownPredicateWindowRankRule() {
        super(RuleType.TF_PUSH_DOWN_PREDICATE_WINDOW_RANK,
                Pattern.create(OperatorType.LOGICAL_FILTER).
                        addChildren(Pattern.create(OperatorType.LOGICAL_WINDOW, OperatorType.PATTERN_LEAF)));
    }

    @Override
    public boolean check(OptExpression input, OptimizerContext context) {
        // This rule introduce a new version of TopNOperator, i.e. PartitionTopNOperator
        // which only supported in pipeline engine, so we cannot apply this rule in non-pipeline engine
        if (!context.getSessionVariable().isEnablePipelineEngine()) {
            return false;
        }

        OptExpression childExpr = input.inputAt(0);
        LogicalWindowOperator windowOperator = childExpr.getOp().cast();

        if (windowOperator.getWindowCall().size() != 1) {
            return false;
        }

        ColumnRefOperator windowCol = Lists.newArrayList(windowOperator.getWindowCall().keySet()).get(0);
        CallOperator callOperator = windowOperator.getWindowCall().get(windowCol);

        // TODO(hcf) we support rank/dense_rank later
        if (!FunctionSet.ROW_NUMBER.equalsIgnoreCase(callOperator.getFnName())) {
            return false;
        }

        if (!childExpr.getInputs().isEmpty() && childExpr.inputAt(0).getOp() instanceof LogicalWindowOperator) {
            OptExpression grandChildExpr = childExpr.inputAt(0);
            LogicalWindowOperator nextWindowOperator = grandChildExpr.getOp().cast();
            // We cannot add topN if the current window function and the next window function are in the same sort group
            return !Objects.equals(windowOperator.getEnforceSortColumns(), nextWindowOperator.getEnforceSortColumns());
        }

        return true;
    }

    @Override
    public List<OptExpression> transform(OptExpression input, OptimizerContext context) {
        LogicalFilterOperator filterOperator = input.getOp().cast();
        List<ScalarOperator> filters = Utils.extractConjuncts(filterOperator.getPredicate());
        OptExpression childExpr = input.inputAt(0);
        LogicalWindowOperator windowOperator = childExpr.getOp().cast();

        ColumnRefOperator windowCol = Lists.newArrayList(windowOperator.getWindowCall().keySet()).get(0);

        List<BinaryPredicateOperator> lessPredicates =
                filters.stream().filter(op -> op instanceof BinaryPredicateOperator)
                        .map(ScalarOperator::<BinaryPredicateOperator>cast)
                        .filter(op -> Objects.equals(BinaryPredicateOperator.BinaryType.LE, op.getBinaryType()) ||
                                Objects.equals(BinaryPredicateOperator.BinaryType.LT, op.getBinaryType()))
                        .filter(op -> Objects.equals(windowCol, op.getChild(0)))
                        .filter(op -> op.getChild(1) instanceof ConstantOperator)
                        .collect(Collectors.toList());

        // TODO(hcf) As for rk < 1 and rk <5, we need to add previous rule to simplify such predicates
        if (lessPredicates.size() != 1) {
            return Collections.emptyList();
        }

        BinaryPredicateOperator lessPredicate = lessPredicates.get(0);
        ConstantOperator rightChild = lessPredicate.getChild(1).cast();
        long limitValue = rightChild.getBigint();

        List<ColumnRefOperator> partitionByColumns = windowOperator.getPartitionExpressions().stream()
                .map(ScalarOperator::<ColumnRefOperator>cast)
                .collect(Collectors.toList());

        // TODO(hcf) we will support multi-partition later
        if (partitionByColumns.size() > 1) {
            return Collections.emptyList();
        }

        // If partition by columns is not empty, then we cannot derive sort property from the SortNode
        // OutputPropertyDeriver will generate PhysicalPropertySet.EMPTY if sortPhase is SortPhase.PARTIAL
        final SortPhase sortPhase = partitionByColumns.isEmpty() ? SortPhase.FINAL : SortPhase.PARTIAL;
        final long limit = partitionByColumns.isEmpty() ? limitValue : Operator.DEFAULT_LIMIT;
        final long partitionLimit = partitionByColumns.isEmpty() ? Operator.DEFAULT_LIMIT : limitValue;
        OptExpression newTopNOptExp = OptExpression.create(new LogicalTopNOperator.Builder()
                .setPartitionByColumns(partitionByColumns)
                .setPartitionLimit(partitionLimit)
                .setOrderByElements(windowOperator.getEnforceSortColumns())
                .setLimit(limit)
                .setSortPhase(sortPhase)
                .build(), childExpr.getInputs());

        OptExpression newWindowOptExp =
                OptExpression.create(new LogicalWindowOperator.Builder().withOperator(windowOperator).build(),
                        newTopNOptExp);

        return Collections.singletonList(
                OptExpression.create(new LogicalFilterOperator.Builder().withOperator(filterOperator).build(),
                        newWindowOptExp));
    }
}
