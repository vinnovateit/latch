package com.vinnovateit.latch.core

import java.util.Properties

object LatchCore {
    val VERSION: String by lazy {
        val properties = Properties()
        val resource = checkNotNull(LatchCore::class.java.getResourceAsStream("/latch-version.properties")) {
            "Missing latch-version.properties"
        }
        resource.use(properties::load)
        checkNotNull(properties.getProperty("version")?.takeIf(String::isNotBlank)) {
            "Missing Latch version"
        }
    }
}
