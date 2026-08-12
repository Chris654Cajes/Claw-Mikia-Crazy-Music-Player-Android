package com.mochimochi.clawmikiacrazy.ui.activities

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.mochimochi.clawmikiacrazy.R
import com.mochimochi.clawmikiacrazy.ui.fragments.EqualizerFragment

class EqualizerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_equalizer)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, EqualizerFragment())
                .commit()
        }

        findViewById<android.view.View>(R.id.btnBack).setOnClickListener {
            finish()
        }
    }
}
