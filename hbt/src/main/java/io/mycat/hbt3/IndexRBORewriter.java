package io.mycat.hbt3;

import io.mycat.calcite.table.MycatLogicTable;
import io.mycat.router.ShardingTableHandler;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.util.mapping.Mappings;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static io.mycat.calcite.CalciteUtls.unCastWrapper;

public class IndexRBORewriter<T> extends SQLRBORewriter {
    public IndexRBORewriter(OptimizationContext optimizationContext) {
        super(optimizationContext);
    }

    @Override
    public RelNode visit(TableScan scan) {
        Optional<T> indexTableView = checkIndex(scan);
        if (indexTableView.isPresent()){
            T t = indexTableView.get();
           return new IndexTableView(scan,(Iterable<Object[]>) t);
        }else {
            return super.visit(scan);
        }
    }

    @Override
    public RelNode visit(LogicalFilter filter) {
        Optional<T> optional = checkIndex(filter);
        if (optional.isPresent()){
            IndexTableView indexTableView = new IndexTableView(filter.getInput(), (Iterable<Object[]>) optional.get());
            return filter.copy(filter.getTraitSet(),indexTableView,filter.getCondition());
        }else {
            return super.visit(filter);
        }
    }

    @Override
    public RelNode visit(LogicalProject project) {
        Optional<T> optional = checkIndex(project);
        if (optional.isPresent()){
            IndexTableView indexTableView = new IndexTableView(project.getInput(), (Iterable<Object[]>) optional.get());
            return project.copy(project.getTraitSet(),
                    indexTableView,project.getProjects(),
                    project.getRowType());
        }else {
            return super.visit(project);
        }
    }


    public  Optional<T> checkIndex(RelNode input) {
        if (checkTable(input)) {
            LogicalTableScan logicalTableScan = (LogicalTableScan) input;
            if (!isSharding(logicalTableScan)) {
                return Optional.empty();
            }
            RelOptTable table = logicalTableScan.getTable();
            MycatLogicTable mycatLogicTable = table.unwrap(MycatLogicTable.class);
            ShardingTableHandler shardingTableHandler = (ShardingTableHandler) mycatLogicTable.getTable();
            return  (Optional<T>)shardingTableHandler.canIndexTableScan();
        }
        if (checkProjectTable(input)) {
            assert input instanceof LogicalProject;
            LogicalProject project = (LogicalProject) input;
            LogicalTableScan tableScan = (LogicalTableScan) project.getInput();
            if (!isSharding(tableScan)) {
                return Optional.empty();
            }
            RelOptTable table = tableScan.getTable();
            MycatLogicTable mycatLogicTable = table.unwrap(MycatLogicTable.class);
            ShardingTableHandler shardingTableHandler = (ShardingTableHandler) mycatLogicTable.getTable();
            return (Optional<T>)shardingTableHandler.canIndexTableScan(map2IntArray(project));
        }
        if (checkProjectFilterTable(input)) {
            assert input instanceof LogicalProject;
            LogicalProject project = (LogicalProject) input;
            LogicalFilter filter = (LogicalFilter) project.getInput();
            LogicalTableScan tableScan = (LogicalTableScan) filter.getInput();
            if (!isSharding(tableScan)) {
                return Optional.empty();
            }
            RelOptTable table = tableScan.getTable();
            MycatLogicTable mycatLogicTable = table.unwrap(MycatLogicTable.class);
            ShardingTableHandler shardingTableHandler = (ShardingTableHandler) mycatLogicTable.getTable();
            List<RexNode> rexNodes = (List<RexNode>) Collections.singletonList(
                    filter.getCondition());
            if (rexNodes.size() == 1) {
                RexNode rexNode = rexNodes.get(0);
                if (rexNode.getKind() == SqlKind.EQUALS) {
                    RexCall rexNode1 = (RexCall) rexNode;
                    List<RexNode> operands = rexNode1.getOperands();
                    RexNode left = operands.get(0);
                    left = unCastWrapper(left);
                    RexNode right = operands.get(1);
                    right = unCastWrapper(right);
                    int index = ((RexInputRef) left).getIndex();
                    Object value = ((RexLiteral) right).getValue2();
                 return  (Optional<T>)shardingTableHandler.canIndexTableScan(map2IntArray(project),
                            new int[]{index},new Object[]{value});
                }
            }
        }
        return Optional.empty();
    }

    private static int[] map2IntArray(LogicalProject project) {
        Mappings.TargetMapping mapping = project.getMapping();
        int[] ints = new int[mapping.getSourceCount()];
        for (int i = 0; i < ints.length; i++) {
            ints[i] = mapping.getSourceOpt(i);
        }
        return ints;
    }

    private static boolean isSharding(LogicalTableScan tableScan) {
        RelOptTable table = tableScan.getTable();
        AbstractMycatTable abstractMycatTable = table.unwrap(AbstractMycatTable.class);
        return abstractMycatTable.isSharding();
    }

    private static boolean checkTable(RelNode input) {
        return input instanceof LogicalTableScan;
    }

    private static boolean checkProjectTable(RelNode input) {
        return input instanceof LogicalProject
                &&
                checkTable(((LogicalProject) input).getInput());
    }

    private static boolean checkProjectFilterTable(RelNode input) {
        return input instanceof LogicalProject
                &&
                ((LogicalProject) input).getInput() instanceof LogicalFilter
                &&
                checkTable(((LogicalFilter) ((LogicalProject) input).getInput()).getInput());
    }
}
