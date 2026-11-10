package io.github.akarimarisa.tumblrdownloader.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import io.github.akarimarisa.tumblrdownloader.R
import io.github.akarimarisa.tumblrdownloader.databinding.ActivityOnboardingBinding
import io.github.akarimarisa.tumblrdownloader.utils.LocaleHelper

class OnboardingActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "onboarding_prefs"
        private const val KEY_DONE = "onboarding_done"
        private const val EXTRA_TUTORIAL = "tutorial_mode"

        fun isDone(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_DONE, false)

        fun markDone(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_DONE, true).apply()
        }

        /**
         * Read-only replay of the guide from the sidebar's "How to use" entry.
         * Same pages, but leaving it never re-marks the first-run flow.
         */
        fun newTutorialIntent(context: Context): Intent =
            Intent(context, OnboardingActivity::class.java).putExtra(EXTRA_TUTORIAL, true)
    }

    lateinit var pages: List<OnboardingPage>
        private set

    /** True when opened from the sidebar instead of the first-run flow. */
    private val tutorialMode: Boolean by lazy {
        intent?.getBooleanExtra(EXTRA_TUTORIAL, false) == true
    }

    private lateinit var binding: ActivityOnboardingBinding

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyToContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pages = listOf(
            OnboardingPage("📋", getString(R.string.onboarding_title_1), getString(R.string.onboarding_desc_1)),
            OnboardingPage("🔐", getString(R.string.onboarding_title_2), getString(R.string.onboarding_desc_2)),
            OnboardingPage("📥", getString(R.string.onboarding_title_3), getString(R.string.onboarding_desc_3)),
            OnboardingPage("☑️", getString(R.string.onboarding_title_4), getString(R.string.onboarding_desc_4)),
            OnboardingPage("🗂", getString(R.string.onboarding_title_5), getString(R.string.onboarding_desc_5))
        )

        setupViewPager()
        setupDots()
        setupButtons()
    }

    private fun setupViewPager() {
        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = pages.size
            override fun createFragment(position: Int) =
                OnboardingFragment.newInstance(position)
        }
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateDots(position)
                // The tutorial replay has no "Get started" step: leaving is
                // always available through the Skip slot.
                binding.btnGetStarted.isVisible =
                    !tutorialMode && position == pages.size - 1
            }
        })
    }

    private fun setupDots() {
        binding.dotsLayout.removeAllViews()
        repeat(pages.size) {
            val tv = TextView(this).apply {
                text = "\u25CF"
                textSize = 12f
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(8, 0, 8, 0)
                layoutParams = lp
            }
            binding.dotsLayout.addView(tv)
        }
        updateDots(0)
    }

    private fun updateDots(current: Int) {
        for (i in 0 until binding.dotsLayout.childCount) {
            val tv = binding.dotsLayout.getChildAt(i) as TextView
            tv.setTextColor(ContextCompat.getColor(this,
                if (i == current) R.color.purple_500 else R.color.gray_300
            ))
        }
    }

    private fun setupButtons() {
        if (tutorialMode) {
            // Replay: the "Skip" slot becomes the only way out and must not
            // re-run (or re-mark) the first-run flow.
            binding.btnSkip.setText(R.string.onboarding_close)
            binding.btnSkip.setOnClickListener { finish() }
            return
        }
        binding.btnSkip.setOnClickListener { finishOnboarding() }
        binding.btnGetStarted.setOnClickListener { finishOnboarding() }
    }

    private fun finishOnboarding() {
        markDone(applicationContext)
        finish()
    }
}

data class OnboardingPage(val emoji: String, val title: String, val description: String)

class OnboardingFragment : Fragment(R.layout.fragment_onboarding_page) {
    companion object {
        private const val ARG_INDEX = "index"

        fun newInstance(index: Int) = OnboardingFragment().apply {
            arguments = Bundle().apply { putInt(ARG_INDEX, index) }
        }
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        val index = arguments?.getInt(ARG_INDEX, 0) ?: 0
        val pages = (requireActivity() as OnboardingActivity).pages
        val page = pages[index]
        view.findViewById<TextView>(R.id.tvEmoji).text = page.emoji
        view.findViewById<TextView>(R.id.tvTitle).text = page.title
        view.findViewById<TextView>(R.id.tvDescription).text = page.description
    }
}
