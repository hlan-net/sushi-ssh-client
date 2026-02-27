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

        val appVersion = getAppVersionInfo()
        binding.appVersionText.text = getString(R.string.about_version, appVersion.name, appVersion.code)

        binding.githubButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO_URL))
            startActivity(intent)
        }
    }

    private fun getAppVersionInfo(): AppVersionInfo {
        return runCatching {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = packageInfo.versionName?.takeIf { it.isNotBlank() }
                ?: getString(R.string.debug_info_unknown)
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            AppVersionInfo(versionName, versionCode.toString())
        }.getOrElse {
            AppVersionInfo(getString(R.string.debug_info_unknown), getString(R.string.debug_info_unknown))
        }
    }

    private data class AppVersionInfo(
        val name: String,
        val code: String
    )

    companion object {
        private const val GITHUB_REPO_URL = "https://github.com/hlan-net/sushi-ssh-client"
    }
}