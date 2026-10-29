package com.fleet.tracker

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {
    lateinit var store: Store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        setContentView(R.layout.activity_main)
        val nav = findViewById<BottomNavigationView>(R.id.nav)
        nav.setOnItemSelectedListener {
            show(when (it.itemId) {
                R.id.t_trip -> TripFragment()
                R.id.t_inv -> InvoiceFragment()
                else -> SyncFragment()
            })
            true
        }
        if (savedInstanceState == null) nav.selectedItemId = R.id.t_trip
    }

    private fun show(f: Fragment) {
        supportFragmentManager.beginTransaction().replace(R.id.host, f).commit()
    }

    override fun onResume() {
        super.onResume()
        if (store.dirty && store.configured()) Syncer.run(store)
    }
}
