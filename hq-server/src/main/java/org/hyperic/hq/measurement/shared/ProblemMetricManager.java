package org.hyperic.hq.measurement.shared;

import java.util.Collection;
import java.util.Map;

import org.hyperic.hq.appdef.shared.AppdefEntityID;
import org.hyperic.hq.measurement.ext.ProblemMetricInfo;
import org.hyperic.hq.measurement.ext.ProblemResourceInfo;
import org.hyperic.hq.measurement.server.session.Measurement;
import org.hyperic.hq.measurement.server.session.MetricProblem;
import org.hyperic.hq.product.MetricValue;
import org.hyperic.util.pager.PageControl;

/**
 * Local interface for ProblemMetricManager.
 */
public interface ProblemMetricManager {

    public void createProblem(Integer mid, long time, int type, Integer additional);

    /**
     * @return may return null if the method is not implemented or the measurement was not found
     * at the specified timestamp
     */
    public MetricProblem getByIdAndTimestamp(Measurement meas, long timestamp);

    /**
     * Return the map of problem counts indexed by template IDs
     */
    public Map<Integer, ProblemMetricInfo> getProblemsByTemplate(Integer[] eids, long begin, long end);

    /**
     * Return the list of problem metrics for an appdef entity
     */
    public ProblemMetricInfo[] getProblemMetrics(AppdefEntityID aid, long begin, long end);

    /**
     * Return the resources having problems in a timeframe with stats on alerts,
     * oob conditions and when within the timeframe the problem awareness
     * begins.
     * @param begin the early boundary of the timeframe of interest
     * @param end the late boundary of the timeframe of interest
     * @return ProblemResourceInfo[]
     */
    public ProblemResourceInfo[] getProblemResources(long begin, long end, PageControl pc);

    public void removeProblems(Collection<Integer> mids);

    public void removeProblems(AppdefEntityID entityId);

    /**
     * Log an OOB instance
     */
    public void processMetricValue(Integer mid, MetricValue mv, int type);

}
