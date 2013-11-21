package com.datatorrent.stram.plan.physical;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datatorrent.api.DefaultPartition;
import com.datatorrent.api.Operator;
import com.datatorrent.api.Operator.InputPort;
import com.datatorrent.api.Partitionable;
import com.datatorrent.api.Partitionable.Partition;
import com.datatorrent.api.Partitionable.PartitionKeys;
import com.datatorrent.stram.plan.logical.LogicalPlan;
import com.datatorrent.stram.plan.logical.LogicalPlan.InputPortMeta;
import com.datatorrent.stram.plan.logical.LogicalPlan.StreamMeta;
import com.google.common.collect.Sets;

/**
 * <p>OperatorPartitions class.</p>
 *
 * @since 0.3.2
 */
public class OperatorPartitions {

  final LogicalPlan.OperatorMeta operatorWrapper;

  public OperatorPartitions(LogicalPlan.OperatorMeta operator) {
    this.operatorWrapper = operator;
  }

  /**
   * The default partitioning applied to operators that do not implement
   * {@link Partitionable} but are configured for partitioning in the
   * DAG.
   */
  public static class DefaultPartitioner
  {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultPartitioner.class);

    public List<Partition<?>> defineInitialPartitions(LogicalPlan.OperatorMeta logicalOperator, int initialPartitionCnt)
    {
      List<Partition<?>> partitions = new ArrayList<Partition<?>>(initialPartitionCnt);
      for (int i=0; i<initialPartitionCnt; i++) {
        Partition<?> p = new DefaultPartition<Operator>(logicalOperator.getOperator());
        partitions.add(p);
      }

      Map<InputPortMeta, StreamMeta> inputs = logicalOperator.getInputStreams();
      if (!inputs.isEmpty() && partitions.size() > 1) {
        //int partitionBits = 0;
        //if (initialPartitionCnt > 0) {
        //  partitionBits = 1 + (int) (Math.log(initialPartitionCnt) / Math.log(2)) ;
        //}
        int partitionBits = (Integer.numberOfLeadingZeros(0)-Integer.numberOfLeadingZeros(initialPartitionCnt-1));
        int partitionMask = 0;
        if (partitionBits > 0) {
          partitionMask = -1 >>> (Integer.numberOfLeadingZeros(-1)) - partitionBits;
        }

        InputPortMeta portMeta = inputs.keySet().iterator().next();

        for (int i=0; i<=partitionMask; i++) {
          // partition the stream that was first connected in the DAG and send full data to remaining input ports
          // this gives control over which stream to partition under default partitioning to the DAG writer
          Partition<?> p = partitions.get(i % partitions.size());
          PartitionKeys pks = p.getPartitionKeys().get(portMeta.getPortObject());
          if (pks == null) {
            // TODO: work with the port meta object instead as this is what we will be using during plan processing anyways
            p.getPartitionKeys().put(portMeta.getPortObject(), new PartitionKeys(partitionMask, Sets.newHashSet(i)));
          } else {
            pks.partitions.add(i);
          }
        }
      }

      return partitions;
    }

    /**
     * Change existing partitioning based on runtime state (load). Unlike
     * implementations of {@link Partitionable}), decisions are made
     * solely based on load indicator and operator state is not
     * considered in the event of partition split or merge.
     *
     * @param partitions
     *          List of new partitions
     * @return
     */
    public List<Partition<?>> repartition(Collection<? extends Partition<?>> partitions) {
      List<Partition<?>> newPartitions = new ArrayList<Partition<?>>();
      HashMap<Integer, Partition<?>> lowLoadPartitions = new HashMap<Integer, Partition<?>>();
      for (Partition<?> p : partitions) {
        int load = p.getLoad();
        if (load < 0) {
          // combine neighboring underutilized partitions
          PartitionKeys pks = p.getPartitionKeys().values().iterator().next(); // one port partitioned
          for (int partitionKey : pks.partitions) {
            // look for the sibling partition by excluding leading bit
            int reducedMask = pks.mask >>> 1;
            String lookupKey = Integer.valueOf(reducedMask) + "-" + Integer.valueOf(partitionKey & reducedMask);
            LOG.debug("pks {} lookupKey {}", pks, lookupKey);
            Partition<?> siblingPartition = lowLoadPartitions.remove(partitionKey & reducedMask);
            if (siblingPartition == null) {
              lowLoadPartitions.put(partitionKey & reducedMask, p);
            } else {
              // both of the partitions are low load, combine
              PartitionKeys newPks = new PartitionKeys(reducedMask, Sets.newHashSet(partitionKey & reducedMask));
              // put new value so the map gets marked as modified
              InputPort<?> port = siblingPartition.getPartitionKeys().keySet().iterator().next();
              siblingPartition.getPartitionKeys().put(port, newPks);
              // add as new partition
              newPartitions.add(siblingPartition);
              //LOG.debug("partition keys after merge {}", siblingPartition.getPartitionKeys());
            }
          }
        } else if (load > 0) {
          // split bottlenecks
          Map<InputPort<?>, PartitionKeys> keys = p.getPartitionKeys();
          Map.Entry<InputPort<?>, PartitionKeys> e = keys.entrySet().iterator().next();

          final int newMask;
          final Set<Integer> newKeys;

          if (e.getValue().partitions.size() == 1) {
            // split single key
            newMask = (e.getValue().mask << 1) | 1;
            int key = e.getValue().partitions.iterator().next();
            int key2 = (newMask ^ e.getValue().mask) | key;
            newKeys = Sets.newHashSet(key, key2);
          } else {
            // assign keys to separate partitions
            newMask = e.getValue().mask;
            newKeys = e.getValue().partitions;
          }

          for (int key : newKeys) {
            Partition<?> newPartition = new DefaultPartition<Operator>(p.getPartitionedInstance());
            newPartition.getPartitionKeys().put(e.getKey(), new PartitionKeys(newMask, Sets.newHashSet(key)));
            newPartitions.add(newPartition);
          }
        } else {
          // leave unchanged
          newPartitions.add(p);
        }
      }
      // put back low load partitions that could not be combined
      newPartitions.addAll(lowLoadPartitions.values());
      return newPartitions;
    }

    /**
     * Adjust the partitions of an input operator (operator with no connected input stream).
     * @param partitions
     * @return
     */
    public static List<Partition<?>> repartitionInputOperator(Collection<? extends Partition<?>> partitions) {
      List<Partition<?>> newPartitions = new ArrayList<Partition<?>>();
      List<Partition<?>> lowLoadPartitions = new ArrayList<Partition<?>>();
      for (Partition<?> p : partitions) {
        int load = p.getLoad();
        if (load < 0) {
          if (!lowLoadPartitions.isEmpty()) {
            newPartitions.add(lowLoadPartitions.remove(0));
          } else {
            lowLoadPartitions.add(p);
          }
        } else if (load > 0) {
          newPartitions.add(new DefaultPartition<Operator>(p.getPartitionedInstance()));
          newPartitions.add(new DefaultPartition<Operator>(p.getPartitionedInstance()));
        } else {
          newPartitions.add(p);
        }
      }
      newPartitions.addAll(lowLoadPartitions);
      return newPartitions;
    }

  }

}