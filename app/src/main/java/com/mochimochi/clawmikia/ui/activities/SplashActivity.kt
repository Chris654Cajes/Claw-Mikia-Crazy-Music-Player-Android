package com.mochimochi.clawmikiacrazy.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mochimochi.clawmikiacrazy.MusicVaultApp
import com.mochimochi.clawmikiacrazy.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_splash)

        lifecycleScope.launch {
            delay(2000)
            val isFirstLaunch =
                MusicVaultApp.instance.prefs.getBoolean(MusicVaultApp.KEY_FIRST_LAUNCH, true)
            val nextActivity =
                if (isFirstLaunch) OnboardingActivity::class.java else MainActivity::class.java

            startActivity(Intent(this@SplashActivity, nextActivity))
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }
}
