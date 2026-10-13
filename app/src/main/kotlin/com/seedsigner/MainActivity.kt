package com.seedsigner

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * v0.0 skeleton: one Activity hosting one screen at a time
 * (docs/code_structure.md). Screen classes and the Controller land in v0.1.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = "SeedSigner"
                setPadding(64, 64, 64, 64)
            }
        )
    }
}
