package io.github.santimattius.android.sample.android

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import io.github.santimattius.android.sample.greeting.GreetingRepository
import javax.inject.Inject

/** Fragment with field injection: Hilt injects it when it attaches, traced as `DemoFragment.members`. */
@AndroidEntryPoint
class DemoFragment : Fragment() {

    @Inject
    lateinit var greetings: GreetingRepository

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        TextView(inflater.context).apply {
            text = "DemoFragment #${System.identityHashCode(this@DemoFragment)} injected with " +
                "GreetingRepository (${greetings.audienceName()})"
        }
}

/**
 * Hosts [DemoFragment]. It exists so the Fragment trace path runs on a real device; it is not exported
 * and has no launcher entry. `FragmentTraceCaptureTest` launches it.
 */
@AndroidEntryPoint
class FragmentHostActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(android.R.id.content, DemoFragment(), TAG)
                .commit()
        }
    }

    companion object {
        const val TAG = "demo-fragment"
    }
}
