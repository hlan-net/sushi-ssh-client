package net.hlan.sushi

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import net.hlan.sushi.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val appVersion = AppUtils.getAppVersionInfo(this)
        binding.appVersionText.text = getString(R.string.about_version, appVersion.name, appVersion.code)

        binding.githubButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO_URL))
            startActivity(intent)
        }
    }

    companion object {
        private const val GITHUB_REPO_URL = "https://github.com/hlan-net/sushi-ssh-client"
    }
}