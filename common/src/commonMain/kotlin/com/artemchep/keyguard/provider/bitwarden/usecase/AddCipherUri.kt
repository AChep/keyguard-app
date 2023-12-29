package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.io.flatten
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.AddUriCipherRequest
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.usecase.AddUriCipher
import com.artemchep.keyguard.common.usecase.CipherUrlDuplicateCheck
import com.artemchep.keyguard.common.usecase.GetAutofillSaveUri
import com.artemchep.keyguard.common.usecase.isEmpty
import com.artemchep.keyguard.common.util.Browsers
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.mapper.toDomain
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyCipherById
import kotlinx.coroutines.flow.first
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class AddUriCipherImpl(
    private val modifyCipherById: ModifyCipherById,
    private val cipherUrlDuplicateCheck: CipherUrlDuplicateCheck,
    private val getAutofillSaveUri: GetAutofillSaveUri,
) : AddUriCipher {
    companion object {
        private const val TAG = "AddUriCipher.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        modifyCipherById = directDI.instance(),
        cipherUrlDuplicateCheck = directDI.instance(),
        getAutofillSaveUri = directDI.instance(),
    )

    override fun invoke(
        request: AddUriCipherRequest,
    ): IO<Boolean> = ioEffect {
        if (request.isEmpty()) {
            return@ioEffect io(false)
        }
        // Check if the option to save uris is actually
        // enabled.
        val shouldSave = getAutofillSaveUri().first()
        if (!shouldSave) {
            return@ioEffect io(false)
        }

        modifyCipherById(
            setOf(request.cipherId),
        ) { model ->
            var new = model

            val oldUris = model.data_.login?.uris.orEmpty()
            val oldUrisDomain = oldUris
                .map { uri ->
                    uri.toDomain()
                }
            val newUris = autofill1(
                // we do not want to add browsers to
                // list of uris
                applicationId = request.applicationId
                    ?.takeIf {
                        it !in Browsers
                    }
                    ?.takeIf {
                        request.webDomain == null ||
                                request.webView == true
                    },
                webDomain = request.webDomain,
                webScheme = request.webScheme,
            )
                .filter { newUri ->
                    val newUriDomain = newUri.toDomain()
                    oldUrisDomain
                        .none { oldUriDomain ->
                            val isDuplicate = cipherUrlDuplicateCheck(oldUriDomain, newUriDomain)
                                .attempt()
                                .bind()
                                .isRight { it != null }
                            isDuplicate
                        }
                }
            if (newUris.isEmpty()) {
                return@modifyCipherById new
            }
            new = new.copy(
                data_ = new.data_.copy(
                    login = new.data_.login?.copy(
                        uris = oldUris + newUris,
                    ),
                ),
            )
            new
        }
            // Report that we have actually modified the
            // ciphers.
            .map { changedCipherIds ->
                request.cipherId in changedCipherIds
            }
    }
        .flatten()
        .effectTap { changed ->
            // TODO: Fix me!!!
//            if (changed) {
//                Toast
//                    .makeText(context, "Added the URI to the item", Toast.LENGTH_SHORT)
//                    .show()
//            }
        }
}

fun List<DSecret.Uri>.autofill(
    applicationId: String? = null,
    webDomain: String? = null,
    webScheme: String? = null,
): List<DSecret.Uri> {
    val existingUris = this
        .toMutableList()
    // If the application we autofill for does not
    // exist there, we append it.
    if (applicationId != null) {
        val androidAppUri = "androidapp://$applicationId"
        val androidAppUriExists = existingUris.any { it.uri == androidAppUri }
        if (!androidAppUriExists) {
            existingUris += DSecret.Uri(
                uri = androidAppUri,
                match = DSecret.Uri.MatchType.default,
            )
        }
    }
    // If the web url we autofill for does not
    // exist there, we append it.
    if (webDomain != null) {
        val webUriExists = existingUris.any { it.uri.contains(webDomain) }
        if (!webUriExists) {
            val webPrefix = webScheme?.let { "$it://" }.orEmpty()
            val webUri = "$webPrefix$webDomain"
            existingUris += DSecret.Uri(
                uri = webUri,
                match = DSecret.Uri.MatchType.default,
            )
        }
    }

    return existingUris
}

fun autofill1(
    applicationId: String? = null,
    webDomain: String? = null,
    webScheme: String? = null,
): List<BitwardenCipher.Login.Uri> {
    val uris = mutableListOf<BitwardenCipher.Login.Uri>()
    // If the application we autofill for does not
    // exist there, we append it.
    if (applicationId != null) {
        val androidAppUri = "androidapp://$applicationId"
        uris += BitwardenCipher.Login.Uri(
            uri = androidAppUri,
            match = BitwardenCipher.Login.Uri.MatchType.Domain,
        )
    }
    // If the web url we autofill for does not
    // exist there, we append it.
    if (webDomain != null) {
        val webPrefix = webScheme?.let { "$it://" }.orEmpty()
        val webUri = "$webPrefix$webDomain"
        uris += BitwardenCipher.Login.Uri(
            uri = webUri,
            match = null,
        )
    }

    return uris
}
