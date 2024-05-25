package com.artemchep.keyguard.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.core.os.BundleCompat
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.largetype.LargeTypeContent
import com.artemchep.keyguard.feature.largetype.produceLargeTypeScreenState
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.NavigationNode
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.platform.recordLog
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.KeepScreenOnEffect
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import org.jetbrains.compose.resources.stringResource
import kotlinx.parcelize.Parcelize

/**
 * A screen that does show the provided text in a Large type
 * style, even if the vault is locked.
 */
class LargeTypeActivity : BaseActivity() {
    companion object {
        private const val KEY_ARGUMENTS = "arguments"

        fun getIntent(
            context: Context,
            args: Args,
        ): Intent = Intent(context, LargeTypeActivity::class.java).apply {
            putExtra(KEY_ARGUMENTS, args)
        }
    }

    @Parcelize
    data class Args(
        val phrases: List<String>,
        val colorize: Boolean,
    ) : Parcelable

    private val args by lazy {
        val extras = intent.extras!!
        val model = BundleCompat
            .getParcelable(extras, KEY_ARGUMENTS, Args::class.java)
        requireNotNull(model) {
            "Large type Activity must be called with arguments provided!"
        }
    }

    private val route by lazy {
        LargeTypeRoute(
            phrases = args.phrases,
            colorize = args.colorize,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordLog("Opened large type activity")
    }

    @Composable
    override fun Content() {
        NavigationNode(
            id = "LargeType:Main",
            route = route,
        )
    }
}

private class LargeTypeRoute(
    val phrases: List<String>,
    val colorize: Boolean,
) : Route {
    @Composable
    override fun Content() {
        LargeTypeScreen(
            phrases = phrases,
            colorize = colorize,
        )
    }
}

@Composable
private fun LargeTypeScreen(
    phrases: List<String>,
    colorize: Boolean,
) {
    val loadableState = produceLargeTypeScreenState(
        com.artemchep.keyguard.feature.largetype.LargeTypeRoute.Args(
            phrases = phrases,
            colorize = colorize,
        ),
    )

    KeepScreenOnEffect()

    val navigationController by rememberUpdatedState(LocalNavigationController.current)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    ScaffoldColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(stringResource(Res.string.largetype_title))
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            val intent = NavigationIntent.Pop
                            navigationController.queue(intent)
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = null,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
        val state = loadableState.getOrNull()
        if (state != null) {
            LargeTypeContent(
                state = state,
            )
        }
    }
}
