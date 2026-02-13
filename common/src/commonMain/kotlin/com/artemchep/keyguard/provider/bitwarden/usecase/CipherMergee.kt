package com.artemchep.keyguard.provider.bitwarden.usecase

import arrow.optics.Lens
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.address1
import com.artemchep.keyguard.common.model.address2
import com.artemchep.keyguard.common.model.address3
import com.artemchep.keyguard.common.model.attachments
import com.artemchep.keyguard.common.model.card
import com.artemchep.keyguard.common.model.city
import com.artemchep.keyguard.common.model.company
import com.artemchep.keyguard.common.model.country
import com.artemchep.keyguard.common.model.email
import com.artemchep.keyguard.common.model.favorite
import com.artemchep.keyguard.common.model.fido2Credentials
import com.artemchep.keyguard.common.model.fields
import com.artemchep.keyguard.common.model.sshKey
import com.artemchep.keyguard.common.model.privateKey
import com.artemchep.keyguard.common.model.publicKey
import com.artemchep.keyguard.common.model.fingerprint
import com.artemchep.keyguard.common.model.firstName
import com.artemchep.keyguard.common.model.identity
import com.artemchep.keyguard.common.model.lastName
import com.artemchep.keyguard.common.model.licenseNumber
import com.artemchep.keyguard.common.model.login
import com.artemchep.keyguard.common.model.middleName
import com.artemchep.keyguard.common.model.notes
import com.artemchep.keyguard.common.model.passportNumber
import com.artemchep.keyguard.common.model.password
import com.artemchep.keyguard.common.model.phone
import com.artemchep.keyguard.common.model.postalCode
import com.artemchep.keyguard.common.model.reprompt
import com.artemchep.keyguard.common.model.ssn
import com.artemchep.keyguard.common.model.state
import com.artemchep.keyguard.common.model.tags
import com.artemchep.keyguard.common.model.title
import com.artemchep.keyguard.common.model.totp
import com.artemchep.keyguard.common.model.type
import com.artemchep.keyguard.common.model.uris
import com.artemchep.keyguard.common.model.username
import com.artemchep.keyguard.common.usecase.CipherMerge
import org.kodein.di.DirectDI

/**
 * @author Artem Chepurnyi
 */
class CipherMergeImpl() : CipherMerge {
    private val mergeRules = Node.Group<DSecret, DSecret>(
        lens = Lens<DSecret, DSecret, DSecret, DSecret>(
            get = { it },
            set = { _, newValue -> newValue },
        ),
        children = listOf(
            Node.Leaf(DSecret.notes),
            Node.Leaf(
                lens = DSecret.type,
                strategy = PickModeStrategy { DSecret.Type.Login },
            ),
            Node.Leaf(
                lens = DSecret.favorite,
                strategy = PickModeStrategy { false },
            ),
            Node.Leaf(
                lens = DSecret.reprompt,
                strategy = PickModeStrategy { false },
            ),
            Node.Leaf(
                lens = DSecret.uris,
                strategy = PickUriStrategy(),
            ),
            Node.Leaf(
                lens = DSecret.fields,
                strategy = PickFieldStrategy(),
            ),
            Node.Leaf(
                lens = DSecret.attachments,
                strategy = PickAttachmentStrategy(),
            ),
            Node.Leaf(
                lens = DSecret.tags,
                strategy = PickTagStrategy(),
            ),
            // types
            Node.Group<DSecret, DSecret.Login>(
                lens = DSecret.login,
                children = listOf(
                    Node.Leaf(DSecret.Login.username),
                    Node.Leaf(DSecret.Login.password),
                    Node.Leaf(DSecret.Login.totp),
                    Node.Leaf(
                        lens = DSecret.Login.fido2Credentials,
                        strategy = PickPasskeyStrategy(),
                    ),
                ),
            ),
            Node.Leaf(
                lens = DSecret.card,
                strategy = PickCardStrategy(),
            ),
            Node.Group<DSecret, DSecret.Identity>(
                lens = DSecret.identity,
                children = listOf(
                    Node.Leaf(DSecret.Identity.title),
                    Node.Leaf(DSecret.Identity.firstName),
                    Node.Leaf(DSecret.Identity.middleName),
                    Node.Leaf(DSecret.Identity.lastName),
                    Node.Leaf(DSecret.Identity.address1),
                    Node.Leaf(DSecret.Identity.address2),
                    Node.Leaf(DSecret.Identity.address3),
                    Node.Leaf(DSecret.Identity.city),
                    Node.Leaf(DSecret.Identity.state),
                    Node.Leaf(DSecret.Identity.postalCode),
                    Node.Leaf(DSecret.Identity.country),
                    Node.Leaf(DSecret.Identity.company),
                    Node.Leaf(DSecret.Identity.email),
                    Node.Leaf(DSecret.Identity.phone),
                    Node.Leaf(DSecret.Identity.ssn),
                    Node.Leaf(DSecret.Identity.username),
                    Node.Leaf(DSecret.Identity.passportNumber),
                    Node.Leaf(DSecret.Identity.licenseNumber),
                ),
            ),
            Node.Group<DSecret, DSecret.SshKey>(
                lens = DSecret.sshKey,
                children = listOf(
                    Node.Leaf(DSecret.SshKey.privateKey),
                    Node.Leaf(DSecret.SshKey.publicKey),
                    Node.Leaf(DSecret.SshKey.fingerprint),
                ),
            ),
        ),
    )

    private sealed interface Node<Input : Any> {
        val lens: Lens<Input, *>

        class Group<Input : Any, Focus : Any>(
            override val lens: Lens<Input, out Focus?>,
            val children: List<Node<Focus>> = emptyList(),
        ) : Node<Input>

        class Leaf<Input : Any, Focus : Any>(
            override val lens: Lens<Input, out Focus?>,
            val strategy: PickStrategy<Focus> = PickModeStrategy(),
        ) : Node<Input>
    }

    @FunctionalInterface
    private interface PickStrategy<T> {
        fun pick(list: List<T>): T
    }

    private class PickModeStrategy<T>(
        val orElse: (() -> T)? = null,
    ) : PickStrategy<T> {
        override fun pick(list: List<T>): T {
            val candidates = kotlin.run {
                val listSorted = list
                    .groupBy { it }
                    .values
                    .sortedByDescending { it.size }
                val first = listSorted[0]
                listSorted
                    .filter { it.size == first.size }
                    .map { it.first() }
            }
            val default = orElse?.invoke()
            return default
                ?.takeIf { it in candidates }
                ?: candidates.first()
        }
    }

    private class PickUriStrategy : PickStrategy<List<DSecret.Uri>> {
        override fun pick(
            list: List<List<DSecret.Uri>>,
        ): List<DSecret.Uri> = list
            .flatten()
            .distinct()
    }

    private class PickFieldStrategy : PickStrategy<List<DSecret.Field>> {
        override fun pick(
            list: List<List<DSecret.Field>>,
        ): List<DSecret.Field> = list
            .flatten()
            .distinct()
    }

    private class PickPasskeyStrategy : PickStrategy<List<DSecret.Login.Fido2Credentials>> {
        override fun pick(
            list: List<List<DSecret.Login.Fido2Credentials>>,
        ): List<DSecret.Login.Fido2Credentials> = list
            .flatten()
            .distinct()
    }

    // FIXME: We do not support merging attachments, so
    //  just invalidate the list of attachments.
    private class PickAttachmentStrategy : PickStrategy<List<DSecret.Attachment>> {
        override fun pick(
            list: List<List<DSecret.Attachment>>,
        ): List<DSecret.Attachment> = emptyList()
    }

    private class PickTagStrategy : PickStrategy<List<String>> {
        override fun pick(
            list: List<List<String>>,
        ): List<String> = list
            .flatten()
            .distinct()
    }

    private class PickCardStrategy : PickStrategy<DSecret.Card> {
        override fun pick(list: List<DSecret.Card>): DSecret.Card = list.first()
    }

    constructor(directDI: DirectDI) : this()

    override fun invoke(
        ciphers: List<DSecret>,
    ): DSecret = kotlin.run {
        val initialCipher = ciphers.first()
            .run {
                copy(
                    login = login ?: DSecret.Login(),
                    identity = identity ?: DSecret.Identity(),
                    card = card ?: DSecret.Card(),
                    sshKey = sshKey ?: DSecret.SshKey(),
                )
            }
        mergeRules.merge(initialCipher, ciphers) as DSecret
    }

    private fun Node<out Any>.merge(
        source: Any,
        data: List<Any>,
    ): Any {
        return when (this) {
            is Node.Group<*, *> -> {
                val lensFixed = lens as Lens<Any, Any?>
                val lensData = data
                    .mapNotNull(lensFixed::getOrNull)
                // We can not merge empty list of sources, so
                // just return the original value.
                if (lensData.isEmpty()) return source

                val lensSource = lensFixed.getOrNull(source)
                // A downside of this approach is that we
                // can not create new objects. :'( If the
                // login is null, we will not create it.
                    ?: return source

                lensFixed.set(
                    source = source,
                    focus = children
                        .fold(lensSource) { s, child ->
                            child.merge(s, lensData)
                        },
                )
            }

            is Node.Leaf<*, *> -> {
                val lensFixed = lens as Lens<Any, Any?>
                val lensData = data
                    .mapNotNull(lensFixed::getOrNull)
                if (lensData.isEmpty()) {
                    // We do not have any children, so
                    // just return the source value.
                    source
                } else {
                    val strategy = strategy as PickStrategy<Any>
                    val focus = strategy.pick(lensData)
                    lensFixed.set(source, focus)
                }
            }
        }
    }
}
