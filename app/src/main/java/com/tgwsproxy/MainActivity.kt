package com.tgwsproxy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tgwsproxy.ui.MainScreen
import com.tgwsproxy.ui.MainViewModel
import com.tgwsproxy.ui.TgWsProxyTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Автозапуск: новое открытие приложения (после закрытия или перезапуска процесса),
        // но не поворот экрана и не возврат из фона.
        if (savedInstanceState == null || !ProxyState.launchHandled) {
            vm.autostartIfNeeded()
        }
        ProxyState.launchHandled = true

        setContent {
            val draft by vm.draft.collectAsStateWithLifecycle()
            TgWsProxyTheme(dynamicColor = draft.dynamicColors) {
                MainScreen(vm)
            }
        }
    }
}
