package io.github.santimattius.android.sample.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.santimattius.android.sample.greeting.GreetingRepository
import javax.inject.Inject

// Injection shapes that have no card and no Run button: instrumented tests launch them to record which
// slices Hilt Trace produces on the real graph (issue #85).

/** `@AndroidEntryPoint` BroadcastReceiver: `Hilt_ShapeReceiver.onReceive` injects it before `onReceive`. */
@AndroidEntryPoint
class ShapeReceiver : BroadcastReceiver() {

    @Inject
    lateinit var greetings: GreetingRepository

    override fun onReceive(context: Context, intent: Intent) {
        greetings.audienceName()
    }
}

/** `@HiltViewModel` obtained with `hiltViewModel()` and no arguments. */
@HiltViewModel
class PlainShapeViewModel @Inject constructor(
    greetings: GreetingRepository,
) : ViewModel() {
    val audience: String = greetings.audienceName()
}

/** `@HiltViewModel` that also receives the `SavedStateHandle` Hilt provides. */
@HiltViewModel
class SavedStateShapeViewModel @Inject constructor(
    val handle: SavedStateHandle,
    greetings: GreetingRepository,
) : ViewModel() {
    val audience: String = greetings.audienceName()
}

/** Compose host: member injection of the activity, then two ViewModels from `hiltViewModel()`. */
@AndroidEntryPoint
class ShapesComposeActivity : ComponentActivity() {

    @Inject
    lateinit var greetings: GreetingRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val plain = hiltViewModel<PlainShapeViewModel>()
            val saved = hiltViewModel<SavedStateShapeViewModel>()
            Text("${plain.audience} ${saved.audience}")
        }
    }
}

/** Parent fragment that attaches a [ShapesChildFragment] to its child fragment manager. */
@AndroidEntryPoint
class ShapesParentFragment : Fragment() {

    @Inject
    lateinit var greetings: GreetingRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            childFragmentManager.beginTransaction().add(ShapesChildFragment(), CHILD_TAG).commit()
        }
    }

    companion object {
        const val CHILD_TAG = "shapes-child"
    }
}

/** Child fragment: it injects in its own `onAttach`, separately from its parent. */
@AndroidEntryPoint
class ShapesChildFragment : Fragment() {

    @Inject
    lateinit var greetings: GreetingRepository
}

/** `DialogFragment` with field injection. */
@AndroidEntryPoint
class ShapesDialogFragment : DialogFragment() {

    @Inject
    lateinit var greetings: GreetingRepository
}

/** Hosts the parent fragment (with its child) and the dialog fragment as siblings. */
@AndroidEntryPoint
class ShapesFragmentHostActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(ShapesParentFragment(), PARENT_TAG)
                .commit()
            ShapesDialogFragment().show(supportFragmentManager, DIALOG_TAG)
        }
    }

    companion object {
        const val PARENT_TAG = "shapes-parent"
        const val DIALOG_TAG = "shapes-dialog"
    }
}
