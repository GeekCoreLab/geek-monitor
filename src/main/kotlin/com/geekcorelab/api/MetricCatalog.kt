package com.geekcorelab.api

object MetricCatalog {
    val hostMetricNames = setOf(
        "cpu_usage_percent", "load_1m", "memory_used_percent", "memory_available_bytes", "swap_used_percent",
        "disk_used_percent", "disk_inode_used_percent", "network_receive_bytes_per_second", "network_transmit_bytes_per_second",
    )

    val databaseMetricNames = setOf(
        "database_connections",
        "database_connection_limit",
        "database_connections_percent",
        "redis_memory_used_bytes",
        "redis_memory_limit_bytes",
        "redis_memory_used_percent",
        "redis_evicted_keys_total",
    )

    val thresholdMetricNames = hostMetricNames + databaseMetricNames

    val labelKeysByMetric = mapOf(
        "disk_used_percent" to "mountpoint",
        "disk_inode_used_percent" to "mountpoint",
        "network_receive_bytes_per_second" to "interface",
        "network_transmit_bytes_per_second" to "interface",
    )
}
