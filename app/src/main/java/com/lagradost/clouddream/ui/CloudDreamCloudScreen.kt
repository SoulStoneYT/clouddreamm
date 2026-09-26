package com.lagradost.clouddream.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.lagradost.clouddream.auth.CloudDreamAuth
import com.lagradost.clouddream.auth.CloudDreamAuthResult
import com.lagradost.clouddream.auth.CloudDreamUser
import com.lagradost.clouddream.auth.toMessageRes
import com.lagradost.clouddream.sync.CloudDreamBookmarkSyncState
import com.lagradost.clouddream.sync.CloudDreamSync
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.mihon.presentation.settings.Preference
import com.mihon.presentation.settings.SearchableSettings
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Phase 2B: the Cloud section of the settings hub, and CloudDream's account entry point.
 *
 * Three mutually exclusive states, all driven by the session reported by
 * [CloudDreamAuth.addUserStateListener]:
 *
 * 1. **Unavailable** — Firebase was never configured for this build, so the screen says so
 *    and offers nothing. CloudStream stays fully usable.
 * 2. **Signed out** — the credential form (email, password, sign in, create account).
 * 3. **Signed in** — the account's email, a **Sync Now** row (Phase 2D.2) and a sign-out
 *    action.
 *
 * There is no Firebase type in this file: every call goes through [CloudDreamAuth] or
 * [CloudDreamSync], and nothing is persisted, logged or cached here. Signing in is opt-in
 * and never happens at startup, so the app is never gated on an account.
 */
object CloudDreamCloudScreen : SearchableSettings {

    @Composable
    override fun getTitleRes(): String = stringResource(R.string.category_cloud)

    @Composable
    override fun getPreferences(): List<Preference> {
        val scope = rememberCoroutineScope()

        // Bound to a local val so the null check below smart-casts.
        val user = rememberCloudDreamUser().value
        val available = CloudDreamAuth.isAvailable
        val groupTitle = stringResource(R.string.pref_category_clouddream_account)

        // Declared before any early return so the remember slots never move.
        var pending by remember { mutableStateOf<CloudDreamAuthAction?>(null) }
        var errorRes by remember { mutableStateOf<Int?>(null) }

        if (!available) {
            return persistentListOf(
                Preference.PreferenceGroup(
                    title = groupTitle,
                    preferenceItems = persistentListOf(
                        Preference.PreferenceItem.TextPreference(
                            title = stringResource(R.string.clouddream_account_status),
                            subtitle = stringResource(R.string.clouddream_account_unavailable),
                            icon = painterResource(R.drawable.ic_outline_account_circle_24),
                        )
                    ),
                )
            )
        }

        val statusRow = Preference.PreferenceItem.TextPreference(
            title = stringResource(
                if (user == null) R.string.clouddream_account_status
                else R.string.clouddream_account_signed_in_as
            ),
            // Fall back to the uid so the account is always identifiable, even if it
            // somehow has no email.
            subtitle = user?.email ?: user?.uid
                ?: stringResource(R.string.clouddream_account_not_signed_in),
            icon = painterResource(R.drawable.ic_outline_account_circle_24),
        )

        return when {
            user == null -> persistentListOf(
                Preference.PreferenceGroup(
                    title = groupTitle,
                    preferenceItems = persistentListOf(
                        statusRow,
                        Preference.PreferenceItem.CustomPreference(
                            title = groupTitle,
                            content = {
                                CloudDreamSignInForm(
                                    pending = pending,
                                    pendingError = errorRes,
                                    onPendingChange = { pending = it },
                                    onError = { errorRes = it },
                                )
                            },
                        ),
                    ),
                )
            )

            else -> persistentListOf(
                Preference.PreferenceGroup(
                    title = groupTitle,
                    preferenceItems = persistentListOf(
                        statusRow,
                        syncNowRow(scope, user != null),
                        Preference.PreferenceItem.TextPreference(
                            title = stringResource(
                                if (pending == CloudDreamAuthAction.SIGN_OUT) {
                                    R.string.clouddream_signing_out
                                } else {
                                    R.string.clouddream_sign_out
                                }
                            ),
                            icon = painterResource(R.drawable.ic_baseline_exit_24),
                            // A disabled PreferenceItem animates out of the layout
                            // instead of greying out, so "busy" is expressed by
                            // dropping onClick rather than by enabled = false.
                            onClick = if (pending != null) {
                                null
                            } else {
                                {
                                    pending = CloudDreamAuthAction.SIGN_OUT
                                    scope.launch {
                                        val result = CloudDreamAuth.signOut()
                                        pending = null
                                        when (result) {
                                            // The session listener swaps back to the
                                            // signed-out form on its own.
                                            is CloudDreamAuthResult.Success -> Unit
                                            is CloudDreamAuthResult.Failure -> showToast(
                                                result.error.toMessageRes(),
                                                Toast.LENGTH_LONG,
                                            )
                                        }
                                    }
                                }
                            },
                        ),
                    ),
                )
            )
        }
    }

    /**
     * The Phase 2D.2 "Sync Now" row, shown only in the signed-in state.
     *
     * It calls exactly the same [CloudDreamBookmarkSyncService.syncNow] the lifecycle
     * triggers use, so a manual sync and an automatic one cannot diverge. The row renders
     * only the coarse [CloudDreamBookmarkSyncState], never a Firestore error, so no
     * implementation detail reaches the user.
     *
     * While a pass is running the row stays visible but drops its `onClick` — the same
     * convention the sign-out row uses, because a disabled `PreferenceItem` would animate
     * out of the layout instead of greying out.
     */
    @Composable
    private fun syncNowRow(scope: CoroutineScope, signedIn: Boolean): Preference.PreferenceItem.TextPreference {
        val service = CloudDreamSync.serviceOrNull()
        val state by (service?.state ?: remember { MutableStateFlow(CloudDreamBookmarkSyncState.Idle) })
            .collectAsState()

        val syncing = state == CloudDreamBookmarkSyncState.Syncing
        val subtitle = when (state) {
            is CloudDreamBookmarkSyncState.Syncing -> stringResource(R.string.clouddream_sync_syncing)
            is CloudDreamBookmarkSyncState.Success -> stringResource(R.string.clouddream_sync_success)
            is CloudDreamBookmarkSyncState.Failure -> stringResource(R.string.clouddream_sync_failed)
            // Idle, and the build-has-no-sync-service case, share this wording.
            is CloudDreamBookmarkSyncState.Idle -> stringResource(R.string.clouddream_sync_idle)
        }

        return Preference.PreferenceItem.TextPreference(
            title = stringResource(R.string.clouddream_sync_now),
            subtitle = subtitle,
            icon = painterResource(R.drawable.baseline_sync_24),
            // No service means sync is not installed (unconfigured build), so the row is
            // present but inert rather than crashing or disappearing mid-list.
            onClick = if (service == null || syncing || !signedIn) {
                null
            } else {
                {
                    scope.launch {
                        // Fire and forget: the row's own state flow reports the outcome,
                        // and a failure must never surface as an error dialog here.
                        service.syncNow()
                    }
                }
            },
        )
    }
}

/**
 * Observes the CloudDream session as Compose state.
 *
 * [CloudDreamAuth.addUserStateListener] fires immediately with the current user, so the
 * initial value is only a placeholder for the unavailable case, where the listener is a
 * no-op and never fires. The listener is always removed on dispose.
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
