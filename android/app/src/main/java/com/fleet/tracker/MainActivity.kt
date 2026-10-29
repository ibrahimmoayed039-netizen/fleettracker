package com.fleet.tracker

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {
    lateinit var store: Store
    private lateinit var nav: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store.get(this)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        setContentView(R.layout.activity_main)
        nav = findViewById(R.id.nav)
        nav.setOnItemSelectedListener {
            show(when (it.itemId) {
                R.id.t_trip -> if (needProfile()) ProfileFragment() else TripFragment()
                R.id.t_inv -> if (needProfile()) ProfileFragment() else InvoiceFragment()
                else -> SyncFragment()
            })
            true
        }
        if (savedInstanceState == null) nav.selectedItemId = R.id.t_trip
        SyncWorker.schedulePeriodic(this)
    }

    /** لا يُسمح بتسجيل الرحلات قبل إكمال بيانات السائق والمركبة */
    private fun needProfile() = store.configured() && !store.profileDone()

    /** بعد حفظ البيانات: العودة لتبويب الرحلة */
    fun afterProfile() {
        if (nav.selectedItemId == R.id.t_trip) show(TripFragment()) else nav.selectedItemId = R.id.t_trip
    }

    fun show(f: Fragment) {
        supportFragmentManager.beginTransaction().replace(R.id.host, f).commit()
    }

    override fun onResume() {
        super.onResume()
        val cur = supportFragmentManager.findFragmentById(R.id.host)
        if (needProfile() && cur != null && cur !is SyncFragment && cur !is ProfileFragment) show(ProfileFragment())
        if (store.dirty && store.configured()) { Syncer.run(store, false); SyncWorker.enqueue(this) }
    }
}
