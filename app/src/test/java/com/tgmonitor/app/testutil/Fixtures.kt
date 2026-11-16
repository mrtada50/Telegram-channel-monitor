package com.tgmonitor.app.testutil

object Fixtures {
    fun load(name: String): String =
        Fixtures::class.java.classLoader!!.getResourceAsStream("fixtures/$name")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
}
