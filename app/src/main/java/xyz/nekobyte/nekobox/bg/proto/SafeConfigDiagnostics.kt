package xyz.nekobyte.nekobox.bg.proto

import xyz.nekobyte.nekobox.fmt.ConfigBuildResult

internal fun safeConfigDiagnostics(config: ConfigBuildResult, pluginConfigCount: Int = 0): String = "config_bytes=${config.config.toByteArray(Charsets.UTF_8).size} " +
    "external_indexes=${config.externalIndex.size} " +
    "traffic_mappings=${config.trafficMap.size} " +
    "profile_tags=${config.profileTagMap.size} " +
    "plugin_configs=$pluginConfigCount"
