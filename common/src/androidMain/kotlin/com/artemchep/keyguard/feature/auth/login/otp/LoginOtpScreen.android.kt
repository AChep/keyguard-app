package com.artemchep.keyguard.feature.auth.login.otp

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.Keep
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import arrow.core.left
import arrow.core.right
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.util.DividerColor
import org.jetbrains.compose.resources.stringResource

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun ColumnScope.LoginOtpScreenContentFido2WebAuthnWebView(
    screenScope: LoginOtpScreenScope,
    state: LoginTwofaState.Fido2WebAuthn,
) {
    val updatedCallbackUrls by rememberUpdatedState(state.callbackUrls)
    val updatedOnComplete by rememberUpdatedState(state.onComplete)

    BaseWebView(
        modifier = Modifier,
        factory = {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val handled = run {
                        val url = request?.url
                            ?: return@run false
                        overrideRequest(url)
                    }
                    return handled || super.shouldOverrideUrlLoading(view, request)
                }

                private fun overrideRequest(uri: Uri): Boolean {
                    val shouldBeHandled = kotlin.run {
                        val uriString = uri.toString()
                        updatedCallbackUrls
                            .any { callbackUrl -> uriString.startsWith(callbackUrl) }
                    }
                    if (shouldBeHandled) run {
                        val callback = updatedOnComplete
                            ?: return@run
                        val data = uri.getQueryParameter("data")
                        if (data != null) {
                            val result = data.right()
                            callback(result)
                        }

                        val error = uri.getQueryParameter("error")
                            ?: "Something went wrong"
                        val result = RuntimeException(error).left()
                        callback(result)
                    }
                    return shouldBeHandled
                }
            }
        },
        url = state.authUrl,
    )
}

@Composable
actual fun ColumnScope.LoginOtpScreenContentFido2WebAuthnBrowser(
    screenScope: LoginOtpScreenScope,
    state: LoginTwofaState.Fido2WebAuthn,
) {
    val updatedOnClick by rememberUpdatedState(state.onBrowser)
    Button(
        modifier = Modifier
            .padding(horizontal = 16.dp),
        enabled = updatedOnClick != null,
        onClick = {
            updatedOnClick?.invoke()
        },
    ) {
        Text(stringResource(Res.string.fido2webauthn_action_go_title))
    }
    ExpandedIfNotEmpty(state.error) { error ->
        Column {
            Spacer(
                modifier = Modifier
                    .height(16.dp),
            )
            Text(
                modifier = Modifier
                    .padding(horizontal = Dimens.horizontalPadding),
                text = error,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    // TODO: Should we show the warning?
//    Spacer(
//        modifier = Modifier
//            .height(32.dp),
//    )
//    Icon(
//        modifier = Modifier
//            .padding(horizontal = Dimens.horizontalPadding),
//        imageVector = Icons.Outlined.Info,
//        contentDescription = null,
//        tint = LocalContentColor.current.combineAlpha(alpha = MediumEmphasisAlpha),
//    )
//    Spacer(
//        modifier = Modifier
//            .height(16.dp),
//    )
//    Text(
//        modifier = Modifier
//            .padding(horizontal = Dimens.horizontalPadding),
//        text = stringResource(
//            Res.string.fido2webauthn_bitwarden_web_vault_version_warning_note,
//            "2023.7.1",
//        ),
//        style = MaterialTheme.typography.bodyMedium,
//        color = LocalContentColor.current.combineAlpha(alpha = MediumEmphasisAlpha),
//    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun ColumnScope.LoginOtpScreenContentDuoWebView(
    screenScope: LoginOtpScreenScope,
    state: LoginTwofaState.Duo,
) {
    val updatedOnComplete by rememberUpdatedState(state.onComplete)
    BaseWebView(
        modifier = Modifier
            .fillMaxWidth()
            .height(400.dp),
        factory = {
            fun createJsInjector(js: String) =
                """javascript:(function () { $js })()"""

            val jsBridgeTag = "keyguardAndroidBridge"
            val jsBridge = JsWebViewDuoBridge { data ->
                val result = data.right()
                updatedOnComplete?.invoke(result)
            }

            val jsCode = """
                window.invokeCSharpAction = function (data) {
                    $jsBridgeTag.onSuccess("" + data);
                };
            """.trimIndent()
            val jsInjector = createJsInjector(jsCode)

            addJavascriptInterface(jsBridge, jsBridgeTag)

            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            webViewClient = object : WebViewClient() {
                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    view?.loadUrl(jsInjector)
                    super.onPageCommitVisible(view, url)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.loadUrl(jsInjector)
                    super.onPageFinished(view, url)
                }
            }
        },
        url = state.authUrl,
    )
}

@Keep
private class JsWebViewDuoBridge(
    private val onSuccess: (String) -> Unit,
) {
    // Called by the JavaScript of the
    // Duo page connector.
    @JavascriptInterface
    fun onSuccess(data: String) = onSuccess.invoke(data)
}

@Composable
private fun BaseWebView(
    modifier: Modifier = Modifier,
    url: String,
    factory: WebView.() -> Unit,
) {
    val shape = MaterialTheme.shapes.extraLarge
    AndroidView(
        modifier = modifier
            .padding(8.dp)
            .clip(shape)
            .border(Dp.Hairline, DividerColor, shape),
        factory = { context ->
            WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                }

                factory(this)
                loadUrl(url)
                tag = url
            }
        },
        update = { webView ->
            val prevUrl = webView.tag
            if (prevUrl != url) {
                webView.loadUrl(url)
            }
        },
    )
}
