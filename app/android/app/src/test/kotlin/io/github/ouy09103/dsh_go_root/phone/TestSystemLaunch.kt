package io.github.ouy09103.dsh_go_root.phone

/** Native Intent-free fake at the SystemApi boundary; used by actual Core/worker tests. */
internal class TestSystemLaunch(
    override val target: SystemTarget,
    val kind: String = "app",
    val spec: IntentSpec? = null,
    override val packageName: String? = null,
) : SystemLaunch
