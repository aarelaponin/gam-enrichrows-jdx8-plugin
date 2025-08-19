package com.fiscaladmin.gam.enrichrows.constants;

/**
 * Framework-level constants for the generic data processing pipeline.
 * These constants are not specific to any particular business domain
 * and can be reused across different implementations.
 */
public final class FrameworkConstants {

    // =====================================================
    // Generic Status Values
    // =====================================================
    public static final String STATUS_NEW = "new";
    public static final String STATUS_ENRICHED = "enriched";
    public static final String STATUS_POSTED = "posted";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_ACTIVE_CAPITAL = "Active";

    // =====================================================
    // Processing Statuses (Generic)
    // =====================================================
    public static final String PROCESSING_STATUS_ENRICHED = "enriched";
    public static final String PROCESSING_STATUS_MANUAL_REVIEW = "manual_review";
    
    // =====================================================
    // Entity Identifiers
    // =====================================================
    public static final String ENTITY_UNKNOWN = "UNKNOWN";
    public static final String ENTITY_SYSTEM = "SYSTEM";
    public static final String INTERNAL_TYPE_UNMATCHED = "UNMATCHED";
    
    // =====================================================
    // System Constants
    // =====================================================
    public static final String SYSTEM_USER = "SYSTEM";
    public static final String PIPELINE_VERSION = "1.0";
    
    // =====================================================
    // Processing Configuration
    // =====================================================
    public static final boolean STOP_ON_ERROR_DEFAULT = true;

    // =====================================================
    // Common Field Names
    // =====================================================
    public static final String FIELD_STATUS = "status";

    // Private constructor to prevent instantiation
    private FrameworkConstants() {
        throw new AssertionError("FrameworkConstants class should not be instantiated");
    }
}