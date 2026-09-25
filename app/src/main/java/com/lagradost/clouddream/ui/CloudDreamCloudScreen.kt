package com.lagradost.clouddream.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.lagradost.clouddream.auth.CloudDreamAuth
import com.lagradost.clouddream.auth.CloudDreamUser
import com.lagradost.cloudstream3.R
import com.mihon.presentation.settings.Preference
import com.mihon.presentation.settings.SearchableSettings
import kotlinx.collections.immutable.persistentListOf

/**
 * Phase 2B: the Cloud section of the settings hub.
 *
 * Deliberately read-only for now. It renders the current CloudDream session state and
 * nothing else — there is no sign-in form, no sign-out button and no credential input
 * yet. Those come in later steps, once this screen is proven to build and display.
 *
 * It is safe in every configuration: when Firebase was never configured the status row
 * says so instead of throwing, which matches the "Firebase is optional" rule documented
 * in CLOUDSYNC.md.
 */
object CloudDreamCloudScreen : SearchableSettings {

    @Composable
    override fun getTitleRes(): String = stringResource(R.string.category_cloud)

    @Composable
    override fun getPreferences(): List<Preference> {
        val available = CloudDreamAuth.isAvailable
        // Bound to a local val so the null check below smart-casts.
        val user = rememberCloudDreamUser().value

        val status = when {
            !available -> stringResource(R.string.clouddream_account_unavailable)
            user == null -> stringResource(R.string.clouddream_account_not_signed_in)
            // Fall back to the uid so a user is always identifiable, even without an email.
            else -> user.email ?: user.uid
        }

        return persistentListOf(
            Preference.PreferenceGroup(
                title = stringResource(R.string.pref_category_clouddream_account),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.clouddream_account_status),
                        subtitle = status,
                        icon = painterResource(R.drawable.ic_outline_account_circle_24),
                    )
                ),
            )
        )
    }
}

/**
 * Observes the CloudDream session as Compose state.
 *
 * [CloudDreamAuth.addUserStateListener] fires immediately with the current user, so the
 * initial value is only a placeholder for the unavailable case, where the listener is a
 * no-op and never fires.
 */
@Composable
private fun rememberCloudDreamUser(): State<CloudDreamUser?> {
    val state = remember { mutableStateOf(CloudDreamAuth.currentUser) }
    DisposableEffect(Unit) {
        val removeListener = CloudDreamAuth.addUserStateListener { state.value = it }
        onDispose { removeListener() }
    }
    return state
}
