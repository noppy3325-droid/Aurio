package jp.noppy.lumamusic

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

internal enum class LauncherIcon(
    private val alias: String,
    @StringRes val label: Int,
    @DrawableRes val preview: Int,
) {
    ROSE("MainActivity", R.string.icon_rose, R.drawable.aurio_logo_rose),
    SAPPHIRE("LauncherSapphire", R.string.icon_sapphire, R.drawable.aurio_logo_sapphire),
    JADE("LauncherJade", R.string.icon_jade, R.drawable.aurio_logo_jade),
    AMETHYST("LauncherAmethyst", R.string.icon_amethyst, R.drawable.aurio_logo_amethyst);

    fun component(context: Context) = ComponentName(context.packageName, "${AurioActivity::class.java.name.substringBeforeLast('.')}.$alias")

    fun apply(context: Context) {
        val manager = context.packageManager
        val states = entries.associateWith { manager.getComponentEnabledSetting(it.component(context)) }
        val changes = entries.filter { it.enabled(states.getValue(it)) != (it == this) }
        if (changes.isEmpty()) return

        fun desired(icon: LauncherIcon) = when {
            icon != this -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            icon == ROSE -> PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            else -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        val flags = PackageManager.DONT_KILL_APP
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.setComponentEnabledSettings(changes.map { icon ->
                PackageManager.ComponentEnabledSetting(icon.component(context), desired(icon), flags)
            })
        } else {
            // Keep an entry visible even if the older API fails to apply the second change.
            val applied = mutableListOf<LauncherIcon>()
            try {
                changes.sortedBy { it != this }.forEach { icon ->
                    manager.setComponentEnabledSetting(icon.component(context), desired(icon), flags)
                    applied.add(icon)
                }
            } catch (error: RuntimeException) {
                applied.asReversed().forEach { icon ->
                    runCatching { manager.setComponentEnabledSetting(icon.component(context), states.getValue(icon), flags) }
                }
                throw error
            }
        }
    }

    private fun enabled(state: Int) = state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
        (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && this == ROSE)

    companion object {
        fun selected(context: Context): LauncherIcon = entries.firstOrNull {
            it.enabled(context.packageManager.getComponentEnabledSetting(it.component(context)))
        } ?: ROSE
    }
}
