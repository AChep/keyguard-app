package com.artemchep.keyguard.ui.tags

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.tags.TagsFieldModel
import com.artemchep.keyguard.feature.tags.addTags
import com.artemchep.keyguard.feature.tags.rankTagSuggestions
import com.artemchep.keyguard.feature.tags.splitTags
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class TagsFieldSceneTest {
    private val catalog = mapOf(
        "work" to 12,
        "finance" to 7,
        "family" to 3,
        "shopping" to 4,
        "2fa" to 9,
        "photos" to 2,
    )

    /** A stand-in for the producer, built on the same logic. */
    private inner class FakeTags(
        initial: List<String>,
    ) {
        var tags by mutableStateOf(initial)
        var draft by mutableStateOf("")
        var revision by mutableIntStateOf(0)

        fun typeDraft(text: String) {
            draft = text
            revision += 1
        }

        fun model() = TagsFieldModel(
            tags = tags.toImmutableList(),
            draft = TextFieldModel(
                text = draft,
                textRevision = revision,
                onChange = { draft = it },
                onSetText = ::typeDraft,
            ),
            suggestions = rankTagSuggestions(draft, tags, catalog).toImmutableList(),
            onCommit = { text ->
                tags = addTags(tags, splitTags(text), catalog)
                typeDraft("")
            },
            onRemove = { tag -> tags = tags - tag },
            onEdit = { tag ->
                tags = tags - tag
                typeDraft(tag)
            },
        )
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    private fun withScene(
        name: String,
        dark: Boolean = false,
        block: SceneScope.() -> Unit,
    ) {
        val fake = FakeTags(listOf("work", "2fa"))
        SwingUtilities.invokeAndWait {
            ImageComposeScene(
                width = 420 * DENSITY,
                height = 260 * DENSITY,
                density = Density(DENSITY.toFloat()),
            ) {
                MaterialExpressiveTheme(
                    colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
                    motionScheme = MotionScheme.expressive(),
                ) {
                    // Skips the keyboard shortcuts service lookup.
                    CompositionLocalProvider(LocalInspectionMode provides true) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.surface,
                        ) {
                            Box(Modifier.padding(16.dp)) {
                                TagsField(value = fake.model())
                            }
                        }
                    }
                }
            }.use { scene ->
                SceneScope(name, scene, fake).block()
            }
        }
    }

    private inner class SceneScope(
        val name: String,
        val scene: ImageComposeScene,
        val fake: FakeTags,
    ) {
        private var time = 0L

        fun settle() {
            repeat(30) {
                time += 33_000_000L
                scene.render(time).close()
            }
        }

        fun snapshot(step: String) {
            settle()
            scene.render(time).use { image ->
                val dir = File("build/tags-field").apply { mkdirs() }
                image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                    File(dir, "$name-$step.png").writeBytes(data.bytes)
                }
            }
        }

        /** Taps the right edge of the field, which focuses the input. */
        fun focus() {
            // The field spans 16..404dp; its first line is centered 33dp below its top.
            val position = Offset((16 + 370) * DENSITY.toFloat(), (16 + 8 + 25) * DENSITY.toFloat())
            scene.sendPointerEvent(PointerEventType.Press, position)
            scene.sendPointerEvent(PointerEventType.Release, position)
            settle()
        }

        fun press(key: Key) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
            settle()
        }
    }

    @Test
    fun `tags field flows`() {
        listOf(false, true).forEach { dark ->
            val name = if (dark) "dark" else "light"
            withScene(name, dark = dark) {
                snapshot("1-rest")

                focus()
                snapshot("2-focused")

                fake.typeDraft("fin")
                snapshot("3-ghost")

                // Tab accepts the completion.
                press(Key.Tab)
                assertEquals(listOf("work", "2fa", "finance"), fake.tags)
                assertEquals("", fake.draft)
                snapshot("4-committed")

                // A different spelling of an existing tag is a duplicate.
                fake.typeDraft("WORK")
                settle()
                press(Key.Enter)
                assertEquals(listOf("work", "2fa", "finance"), fake.tags)
                assertEquals("", fake.draft)

                // A new spelling of a vault tag takes the vault's spelling.
                fake.typeDraft("Shopping")
                settle()
                press(Key.Enter)
                assertEquals(listOf("work", "2fa", "finance", "shopping"), fake.tags)

                // The first Backspace arms the last chip, the second removes it.
                press(Key.Backspace)
                assertEquals(4, fake.tags.size)
                snapshot("5-armed")
                press(Key.Backspace)
                assertEquals(listOf("work", "2fa", "finance"), fake.tags)

                // The arrow keys pick a suggestion.
                press(Key.DirectionDown)
                snapshot("6-highlighted")
                press(Key.Enter)
                assertEquals(4, fake.tags.size)
                snapshot("7-picked")
            }
        }
    }

    private companion object {
        const val DENSITY = 2
    }
}
