package io.github.vitalyostanin.markdownorg

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vitalyostanin.markdownorg.ui.AgendaScreen
import io.github.vitalyostanin.markdownorg.ui.AgendaViewModel
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MarkdownOrgTheme {
                val model: AgendaViewModel = viewModel(factory = AgendaViewModel.Factory)
                val state by model.state.collectAsStateWithLifecycle()

                AgendaScreen(
                    state = state,
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .consumeWindowInsets(WindowInsets.safeDrawing),
                )
            }
        }
    }
}
