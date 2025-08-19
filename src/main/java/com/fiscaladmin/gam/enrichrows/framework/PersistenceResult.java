package com.fiscaladmin.gam.enrichrows.framework;

import java.util.Map;
import java.util.HashMap;

/**
 * Result of a data persistence operation
 */
public class PersistenceResult {
    private boolean success;
    private String recordId;
    private String message;
    private String targetStorage;
    private Map<String, Object> metadata;
    
    public PersistenceResult(boolean success) {
        this.success = success;
        this.metadata = new HashMap<>();
    }
    
    public PersistenceResult(boolean success, String recordId, String message) {
        this.success = success;
        this.recordId = recordId;
        this.message = message;
        this.metadata = new HashMap<>();
    }
    
    // Getters and setters
    public boolean isSuccess() { 
        return success; 
    }
    
    public void setSuccess(boolean success) { 
        this.success = success; 
    }
    
    public String getRecordId() { 
        return recordId; 
    }
    
    public void setRecordId(String recordId) { 
        this.recordId = recordId; 
    }
    
    public String getMessage() { 
        return message; 
    }
    
    public void setMessage(String message) { 
        this.message = message; 
    }
    
    public String getTargetStorage() { 
        return targetStorage; 
    }
    
    public void setTargetStorage(String targetStorage) { 
        this.targetStorage = targetStorage; 
    }
    
    public Map<String, Object> getMetadata() { 
        return metadata; 
    }
    
    public void setMetadata(Map<String, Object> metadata) { 
        this.metadata = metadata; 
    }
    
    public void addMetadata(String key, Object value) {
        if (metadata == null) {
            metadata = new HashMap<>();
        }
        metadata.put(key, value);
    }
}