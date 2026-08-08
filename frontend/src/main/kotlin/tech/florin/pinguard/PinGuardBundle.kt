package tech.florin.pinguard

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls
private const val BUNDLE = "messages.PinGuardBundle"

internal object PinGuardBundle {

    private val INSTANCE = DynamicBundle(PinGuardBundle::class.java, BUNDLE)

    @Nls
    fun message(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any,
    ): String = INSTANCE.getMessage(key, *params)
}
