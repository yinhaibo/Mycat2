/**
 * Copyright (C) <2020>  <chen junwen>
 * <p>
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with this program.  If
 * not, see <http://www.gnu.org/licenses/>.
 */
package io.mycat.calcite.physical;


import com.google.common.collect.Iterators;
import io.mycat.calcite.*;
import io.mycat.calcite.logical.MycatView;
import org.apache.calcite.DataContext;
import org.apache.calcite.adapter.enumerable.JavaRowFormat;
import org.apache.calcite.adapter.enumerable.PhysType;
import org.apache.calcite.adapter.enumerable.PhysTypeImpl;
import org.apache.calcite.linq4j.*;
import org.apache.calcite.linq4j.function.Function1;
import org.apache.calcite.linq4j.tree.*;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rex.RexDynamicParam;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.runtime.NewMycatDataContext;
import org.apache.calcite.util.BuiltInMethod;
import org.apache.calcite.util.Pair;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.*;

public class MycatMergeSort extends Sort implements MycatRel {

    private  static final Method METHOD = Types.lookupMethod(MycatMergeSort.class,
            "orderBy",List .class,
            Function1 .class, Comparator .class, int.class, int.class);

    protected MycatMergeSort(RelOptCluster cluster,
                          RelTraitSet traits,
                          RelNode child,
                          RelCollation collation,
                          RexNode offset,
                          RexNode fetch) {
        super(cluster, traits, child, collation, offset, fetch);
    }
    public static MycatMergeSort create(RelTraitSet traits, RelNode child, RelCollation collation, RexNode offset, RexNode fetch) { return new MycatMergeSort(
                child.getCluster(),
                traits.replace(MycatConvention.INSTANCE),
                child,
                collation,
                offset,
                fetch
                );
    }
    @Override
    public ExplainWriter explain(ExplainWriter writer) {
        writer.name("MycatMergeSort").into();
        for (RelNode relNode : getInputs()) {
            MycatRel relNode1 = (MycatRel) relNode;
            relNode1.explain(writer);
        }
        return writer.ret();
    }

    @Override
    public Executor implement(ExecutorImplementor implementor) {
        return implementor.implement(this);
    }

    @Override
    public Sort copy(RelTraitSet traitSet, RelNode newInput, RelCollation newCollation, RexNode offset, RexNode fetch) {
        return new MycatMergeSort(getCluster(), traitSet, newInput, newCollation, offset, fetch);
    }

    final static Method GET_ENUMERABLES =
            Types.lookupMethod(NewMycatDataContext.class,
                    "getEnumerables",org.apache.calcite.rel.RelNode.class);
    @Override
    public Result implement(MycatEnumerableRelImplementor implementor, Prefer pref) {
        implementor.collectLeafRelNode(this.getInput());
        Expression inputExpression = implementor.stash((MycatView) this.getInput(), MycatView.class);
        final BlockBuilder builder = new BlockBuilder();
        Expression listExpression = builder.append("list", Expressions.call(
                DataContext.ROOT, GET_ENUMERABLES, inputExpression));
        final PhysType physType =
                PhysTypeImpl.of(
                        implementor.getTypeFactory(),
                        getRowType(),
                        JavaRowFormat.ARRAY);

        final PhysType inputPhysType = physType;
        final Pair<Expression, Expression> pair =
                inputPhysType.generateCollationKey(this.collation.getFieldCollations());

        final Expression fetchVal;
        if (this.fetch == null) {
            fetchVal = Expressions.constant(Integer.valueOf(Integer.MAX_VALUE));
        } else {
            fetchVal = getExpression(this.fetch);
        }

        final Expression offsetVal = this.offset == null ? Expressions.constant(Integer.valueOf(0))
                : getExpression(this.offset);

        builder.add(
                Expressions.return_(
                        null, Expressions.call(
                                METHOD, Expressions.list(
                                        listExpression,
                                        builder.append("keySelector", pair.left))
                                        .appendIfNotNull(builder.appendIfNotNull("comparator", pair.right))
                                        .appendIfNotNull(
                                                builder.appendIfNotNull("offset",
                                                        Expressions.constant(offsetVal)))
                                        .appendIfNotNull(
                                                builder.appendIfNotNull("fetch",
                                                        Expressions.constant(fetchVal)))
                        )));
        return implementor.result(physType, builder.toBlock());
    }
    public static Expression getExpression(RexNode rexNode) {
        if (rexNode instanceof RexDynamicParam) {
            final RexDynamicParam param = (RexDynamicParam) rexNode;
            return Expressions.convert_(
                    Expressions.call(DataContext.ROOT,
                            BuiltInMethod.DATA_CONTEXT_GET.method,
                            Expressions.constant("?" + param.getIndex())),
                    Integer.class);
        } else {
            return Expressions.constant(RexLiteral.intValue(rexNode));
        }
    }
    public static <TSource, TKey> Enumerable<TSource> orderBy(
            List<Enumerable<TSource>> sources,
            Function1<TSource, TKey> keySelector,
            Comparator<TKey> comparator,
            int offset, int fetch) {
        Enumerable<TSource> tSources = Linq4j.asEnumerable(new Iterable<TSource>() {
            @NotNull
            @Override
            public Iterator<TSource> iterator() {
                List<Iterator<TSource>> list = new ArrayList<>();
                for (Enumerable<TSource> source : sources) {
                    list.add(source.iterator());
                }

                return Iterators.<TSource>mergeSorted(list, (o1, o2) -> {
                    TKey left = keySelector.apply(o1);
                    TKey right = keySelector.apply(o2);
                    return comparator.compare(left, right);
                });
            }
        });
        tSources=EnumerableDefaults.skip(tSources,offset);
        tSources = EnumerableDefaults.take(tSources,fetch);
        return tSources;
    }
    @Override
    public boolean isSupportStream() {
        return true;
    }
}