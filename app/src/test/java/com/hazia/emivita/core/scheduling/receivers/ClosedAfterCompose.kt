package com.hazia.emivita.core.scheduling.receivers

import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule

/**
 * [compose], with [harness] closed only after it has torn down. JUnit runs `@After`
 * before a rule's own teardown, so closing the database there left the composition
 * collecting Room flows from a closed pool: "the connection pool has been closed",
 * on a slow CI runner only (§7 R108).
 */
fun closedAfterCompose(harness: ReceiverHarness, compose: TestRule): TestRule =
    RuleChain.outerRule(object : ExternalResource() {
        override fun after() = harness.close()
    }).around(compose)
