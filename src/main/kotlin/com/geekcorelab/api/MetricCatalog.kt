package com.geekcorelab.api

object MetricCatalog {
    val hostMetricNames = setOf(
        "cpu_usage_percent", "load_1m", "memory_used_percent", "memory_available_bytes", "swap_used_percent",
        "disk_used_percent", "disk_inode_used_percent", "network_receive_bytes_per_second", "network_transmit_bytes_per_second",
    )

    val labelKeysByMetric = mapOf(
        "disk_used_percent" to "mountpoint",
        "disk_inode_used_percent" to "mountpoint",
        "network_receive_bytes_per_second" to "interface",
        "network_transmit_bytes_per_second" to "interface",
    )
}
