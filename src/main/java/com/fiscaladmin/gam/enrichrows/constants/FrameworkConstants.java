package com.fiscaladmin.gam.enrichrows.constants;

/**
 * Framework-level constants for the generic data processing pipeline.
 * These constants are not specific to any particular business domain
 * and can be reused across different implementations.
 */
public final class FrameworkConstants {

    // =====================================================
    // MDM Status Values (for master data lookups, not lifecycle)
    // =====================================================
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_ACTIVE_CAPITAL = "Active";

    // =====================================================
    // Entity Identifiers
    // =====================================================
    public static final String ENTITY_UNKNOWN = "UNKNOWN";
    public static final String ENTITY_SYSTEM = "SYSTEM";
    public static final String INTERNAL_TYPE_UNMATCHED = "UNMATCHED";

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