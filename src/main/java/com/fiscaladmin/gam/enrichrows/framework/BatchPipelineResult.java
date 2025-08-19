package com.fiscaladmin.gam.enrichrows.framework;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class BatchPipelineResult {
    private int totalTransactions;
    private int successCount;
    private int failureCount;
    private Date startTime;
    private Date endTime;
    private final List<PipelineResult> results = new ArrayList<>();

    public void addResult(PipelineResult result) {
        results.add(result);
    }

    public long getElapsedTimeMillis() {
        if (startTime != null && endTime != null) {
            return endTime.getTime() - startTime.getTime();
        }
        return 0;
    }

    // Getters and setters
    public int getTotalTransactions() { return totalTransactions; }
    public void setTotalTransactions(int totalTransactions) { this.totalTransactions = totalTransactions; }

    public int getSuccessCount() { return successCount; }
    public void setSuccessCount(int successCount) { this.successCount = successCount; }

    public int getFailureCount() { return failureCount; }
    public void setFailureCount(int failureCount) { this.failureCount = failureCount; }

    public Date getStartTime() { return startTime; }
    public void setStartTime(Date startTime) { this.startTime = startTime; }

    public Date getEndTime() { return endTime; }
    public void setEndTime(Date endTime) { this.endTime = endTime; }

    public List<PipelineResult> getResults() { return results; }
}
