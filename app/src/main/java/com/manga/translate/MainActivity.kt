package com.manga.translate

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayoutMediator
import com.manga.translate.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var pagerAdapter: MainPagerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pagerAdapter = MainPagerAdapter(this)
        binding.mainPager.adapter = pagerAdapter
        binding.mainPager.isUserInputEnabled = true
        TabLayoutMediator(binding.mainTabs, binding.mainPager) { tab, position ->
            tab.setText(pagerAdapter.getTitleRes(position))
        }.attach()
        binding.mainPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.mainPager.isUserInputEnabled = position != MainPagerAdapter.READING_INDEX
            }
        })
    }

    fun switchToTab(index: Int) {
        binding.mainPager.setCurrentItem(index, true)
    }
}
