package com.lagradost.clouddream.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.lagradost.clouddream.auth.CloudDreamAuth
import com.lagradost.clouddream.auth.CloudDreamAuthResult
import com.lagradost.clouddream.auth.toMessageRes
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream4.compose.TV
import com.lagradost.cloudstream4.compose.focusOutline
import com.lagradost.cloudstream4.compose.isLayout
import com.mihon.material.padding
import kotlinx.coroutines.launch

/** Which credential operation is currently running, if any. */
internal enum class CloudDreamAuthAction { SIGN_IN, CREATE_ACCOUNT, SIGN_OUT }

/** Firebase rejects passwords shorter than this, so there is no point sending one. */
private const val MIN_PASSWORD_LENGTH = 6

/**
 * The signed-out credential form: email, password, sign in and create account.
 *
 * Credential handling, deliberately enforced here:
 * - the password lives in a plain [remember], never
 *   [androidx.compose.runtime.saveable.rememberSaveable], so it is never written into a
 *   saved-instance-state bundle;
 * - it is cleared as soon as an operation succeeds, and again when the form leaves
 *   composition, so a backgrounded screen never holds a credential;
 * - nothing is ever logged, and CloudDream stores no credential of its own.
 *
 * All authentication goes through [CloudDreamAuth]; this composable never references a
 * Firebase type.
 *
 * @param pending the operation currently running, or null when idle
 * @param pendingError a string resource describing the last failure, or null
 * @param onPendingChange reports which operation is running so the caller can keep the
 * rest of the screen in sync
 * @param onError receives a string resource for a failure, or null to clear it
 */
@Composable
internal fun CloudDreamSignInForm(
    pending: CloudDreamAuthAction?,
    pendingError: Int?,
    onPendingChange: (CloudDreamAuthAction?) -> Unit,
    onError: (Int?) -> Unit,
) {
    val scope = rememberCoroutineScope()

    // Plain remember on purpose: see the KDoc above about saved state.
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    val busy = pending != null
    val onTv = isLayout(TV)

    // Wipe the password whenever this form leaves composition.
    DisposableEffect(Unit) {
        onDispose { password = "" }
    }

    val submit: (CloudDreamAuthAction) -> Unit = { action ->
        if (!busy) {
            val isCreate = action == CloudDreamAuthAction.CREATE_ACCOUNT
            val localError = validateInput(email, password, isCreate)
            if (localError != null) {
                onError(localError)
            } else {
                onError(null)
                onPendingChange(action)
                scope.launch {
                    val result = if (isCreate) {
                        CloudDreamAuth.createAccountWithEmailAndPassword(email, password)
                    } else {
                        CloudDreamAuth.signInWithEmailAndPassword(email, password)
                    }
                    onPendingChange(null)
                    when (result) {
                        // On success the session listener flips the screen to the
                        // signed-in state, which disposes this form and drops the password.
                        is CloudDreamAuthResult.Success -> password = ""
                        is CloudDreamAuthResult.Failure -> onError(result.error.toMessageRes())
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.medium)
            .padding(vertical = MaterialTheme.padding.small),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        OutlinedTextField(
            value = email,
            onValueChange = {
                email = it
                onError(null)
            },
            label = { Text(text = stringResource(R.string.clouddream_email)) },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .focusOutline(enabled = onTv),
        )

        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                onError(null)
            },
            label = { Text(text = stringResource(R.string.clouddream_password)) },
            singleLine = true,
            enabled = !busy,
            visualTransformation = if (passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }, enabled = !busy) {
                    Icon(
                        painter = painterResource(
                            // Show the icon for the action, not the current state.
                            if (passwordVisible) R.drawable.visibility_off_24px
                            else R.drawable.visibility_24px
                        ),
                        contentDescription = stringResource(
                            if (passwordVisible) R.string.clouddream_hide_password
                            else R.string.clouddream_show_password
                        ),
                    )
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { submit(CloudDreamAuthAction.SIGN_IN) },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .focusOutline(enabled = onTv),
        )

        // A single error line for the whole form, because many failures (wrong
        // credentials, no network) are not attributable to one field.
        pendingError?.let { errorRes ->
            Text(
                text = stringResource(errorRes),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = MaterialTheme.padding.extraSmall),
            )
        }

        if (busy) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onBackground,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }

        Button(
            onClick = { submit(CloudDreamAuthAction.SIGN_IN) },
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .focusOutline(enabled = onTv),
        ) {
            Text(
                text = stringResource(
                    if (pending == CloudDreamAuthAction.SIGN_IN) R.string.clouddream_signing_in
                    else R.string.clouddream_sign_in
                )
            )
        }

        TextButton(
            onClick = { submit(CloudDreamAuthAction.CREATE_ACCOUNT) },
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .focusOutline(enabled = onTv),
        ) {
            Text(
                text = stringResource(
                    if (pending == CloudDreamAuthAction.CREATE_ACCOUNT) {
                        R.string.clouddream_creating_account
                    } else {
                        R.string.clouddream_create_account
                    }
                )
            )
        }
    }
}

/**
 * Cheap local validation, so an obviously malformed credential never costs a network
 * round trip. Firebase stays the authority and [CloudDreamAuth] still maps whatever it
 * rejects afterwards.
 */
private fun validateInput(email: String, password: String, isCreate: Boolean): Int? = when {
    email.isBlank() -> R.string.clouddream_error_email_required
    !looksLikeEmail(email) -> R.string.clouddream_error_email_invalid
    password.isBlank() -> R.string.clouddream_error_password_required
    isCreate && password.length < MIN_PASSWORD_LENGTH -> R.string.clouddream_error_password_too_short
    else -> null
}

/**
 * Intentionally loose: it rejects the obvious mistakes without trying to out-guess
 * RFC 5322, which would risk turning away addresses Firebase would have accepted.
 */
private fun looksLikeEmail(email: String): Boolean {
    val trimmed = email.trim()
    if (trimmed.any { it.isWhitespace() }) return false
    val at = trimmed.indexOf('@')
    if (at <= 0 || at != trimmed.lastIndexOf('@') || at == trimmed.length - 1) return false
    // Every public mail domain has a dot, so requiring one is safe here.
    return trimmed.substring(at + 1).contains('.')
}
