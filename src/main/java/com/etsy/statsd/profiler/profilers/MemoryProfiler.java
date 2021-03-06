package com.etsy.statsd.profiler.profilers;

import com.etsy.statsd.profiler.Arguments;
import com.etsy.statsd.profiler.Profiler;
import com.etsy.statsd.profiler.reporter.Reporter;

import java.lang.management.*;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Profiles memory usage and GC statistics
 *
 * @author Andrew Johnson
 */
public class MemoryProfiler extends Profiler {
    public static final long PERIOD = 10;

    private MemoryMXBean memoryMXBean;
    private List<GarbageCollectorMXBean> gcMXBeans;
    private HashMap<GarbageCollectorMXBean, AtomicLong> gcTimes = new HashMap<>();
    private ClassLoadingMXBean classLoadingMXBean;
    private List<MemoryPoolMXBean> memoryPoolMXBeans;

    public MemoryProfiler(Reporter reporter, Arguments arguments) {
        super(reporter, arguments);
        memoryMXBean = ManagementFactory.getMemoryMXBean();
        gcMXBeans = ManagementFactory.getGarbageCollectorMXBeans();
        classLoadingMXBean = ManagementFactory.getClassLoadingMXBean();
        memoryPoolMXBeans = ManagementFactory.getMemoryPoolMXBeans();

        for (GarbageCollectorMXBean b : gcMXBeans) gcTimes.put(b, new AtomicLong());
    }

    /**
     * Profile memory usage and GC statistics
     */
    @Override
    public void profile() {
        recordStats();
    }

    @Override
    public void flushData() {
        recordStats();
    }

    @Override
    public long getPeriod() {
        return PERIOD;
    }

    @Override
    public TimeUnit getTimeUnit() {
        return TimeUnit.SECONDS;
    }

    @Override
    protected void handleArguments(Arguments arguments) { /* No arguments needed */ }

    /**
     * Records all memory statistics
     */
    private void recordStats() {
        int finalizationPendingCount = memoryMXBean.getObjectPendingFinalizationCount();
        MemoryUsage heap = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memoryMXBean.getNonHeapMemoryUsage();

        recordGaugeValue("pending-finalization-count", finalizationPendingCount);
        recordMemoryUsage("heap.total", heap);
        recordMemoryUsage("nonheap.total", nonHeap);

        for (GarbageCollectorMXBean gcMXBean : gcMXBeans) {
            recordGaugeValue("gc." + gcMXBean.getName() + ".count", gcMXBean.getCollectionCount());

            final long time = gcMXBean.getCollectionTime();
            final long prevTime = gcTimes.get(gcMXBean).get();
            final long runtime = time - prevTime;

            recordGaugeValue("gc." + gcMXBean.getName() + ".time", time);
            recordGaugeValue("gc." + gcMXBean.getName() + ".runtime", runtime);

            if (runtime > 0) gcTimes.get(gcMXBean).set(time);
        }

        int loadedClassCount = classLoadingMXBean.getLoadedClassCount();
        long totalLoadedClassCount = classLoadingMXBean.getTotalLoadedClassCount();
        long unloadedClassCount = classLoadingMXBean.getUnloadedClassCount();

        recordGaugeValue("loaded-class-count", loadedClassCount);
        recordGaugeValue("total-loaded-class-count", totalLoadedClassCount);
        recordGaugeValue("unloaded-class-count", unloadedClassCount);

        for (MemoryPoolMXBean memoryPoolMXBean: memoryPoolMXBeans) {
            String type = poolTypeToMetricName(memoryPoolMXBean.getType());
            String name = poolNameToMetricName(memoryPoolMXBean.getName());
            String prefix = type + '.' + name;
            MemoryUsage usage = memoryPoolMXBean.getUsage();

            recordMemoryUsage(prefix, usage);
        }
    }

    /**
     * Records memory usage
     *
     * @param prefix The prefix to use for this object
     * @param memory The MemoryUsage object containing the memory usage info
     */
    private void recordMemoryUsage(String prefix, MemoryUsage memory) {
        recordGaugeValue(prefix + ".init", memory.getInit());
        recordGaugeValue(prefix + ".used", memory.getUsed());
        recordGaugeValue(prefix + ".committed", memory.getCommitted());
        recordGaugeValue(prefix + ".max", memory.getMax());
    }

    /**
     * Formats a MemoryType into a valid metric name
     *
     * @param memoryType a MemoryType
     * @return a valid metric name
     */
    private String poolTypeToMetricName(MemoryType memoryType) {
        switch (memoryType) {
            case HEAP:
                return "heap";
            case NON_HEAP:
                return "nonheap";
            default:
                return "unknown";
        }
    }

    /**
     * Formats a pool name into a valid metric name
     *
     * @param poolName a pool name
     * @return a valid metric name
     */
    private String poolNameToMetricName(String poolName) {
        return poolName.toLowerCase().replaceAll("\\s+", "-");
    }
}
