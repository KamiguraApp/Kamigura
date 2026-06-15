package li.mof.kamigura

import android.os.Bundle
import android.content.pm.ApplicationInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.compose.rememberNavController
import coil.ImageLoader
import coil.compose.LocalImageLoader
import li.mof.kamigura.ui.theme.KamiguraTheme
import kotlinx.coroutines.launch
import li.mof.kamigura.library.LibraryScreen
import li.mof.kamigura.reader.ReaderScreen
import li.mof.kamigura.series.ChapterPickScreen
import li.mof.kamigura.series.SeriesScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sessionStore = KavitaSessionStore(this)
        val settingsStore = AppSettingsStore(this)
        val allowIntentLogin = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val loginDefaults = LoginDefaults(
            baseUrl = if (allowIntentLogin) intent.getStringExtra("serverUrl").orEmpty() else "",
            apiKey = if (allowIntentLogin) intent.getStringExtra("apiKey").orEmpty() else "",
            username = if (allowIntentLogin) intent.getStringExtra("username").orEmpty() else "",
            password = if (allowIntentLogin) intent.getStringExtra("password").orEmpty() else "",
            autoLogin = allowIntentLogin && intent.getBooleanExtra("autoLogin", false)
        )

        setContent {
            KamiguraTheme {
                AppRoot(sessionStore, settingsStore, loginDefaults)
            }
        }
    }
}

@Composable
fun AppRoot(
    sessionStore: KavitaSessionStore,
    settingsStore: AppSettingsStore,
    loginDefaults: LoginDefaults = LoginDefaults()
) {
    val nav = rememberNavController()
    val ctx = LocalContext.current

    var imageLoader by remember { mutableStateOf<ImageLoader?>(null) }
    var sessionRevision by remember { mutableIntStateOf(0) }

    suspend fun refreshActiveServer() {
        sessionRevision += 1
        imageLoader = try {
            val session = sessionStore.load()
            if (session.baseUrl.isBlank() || (session.jwt.isBlank() && session.apiKey.isBlank())) {
                null
            } else {
                val client = KavitaClient(ctx, sessionStore)
                val (_, okHttp) = client.buildApi()
                client.buildImageLoader(okHttp)
            }
        } catch (_: Throwable) {
            null
        }
    }

    CompositionLocalProvider(
        LocalImageLoader provides (imageLoader ?: ImageLoader(ctx))
    ) {
        NavHost(navController = nav, startDestination = "login") {
            composable("login") {
                LoginScreen(
                    sessionStore = sessionStore,
                    defaults = loginDefaults,
                    onLoggedIn = {
                        val client = KavitaClient(ctx, sessionStore)
                        val (_, okHttp) = client.buildApi()
                        imageLoader = client.buildImageLoader(okHttp)
                        sessionRevision += 1
                        nav.navigate("libraries") {
                            popUpTo("login") { inclusive = true }
                        }
                    },
                    onOpenSettings = { nav.navigate("settings") },
                    onOpenServerSettings = { nav.navigate("settings/server") }
                )
            }

            composable("libraries") {
                LibraryScreen(
                    sessionStore = sessionStore,
                    sessionRevision = sessionRevision,
                    onOpenSettings = { nav.navigate("settings") },
                    onSelectLibrary = { lib -> nav.navigate("series/${lib.id}/${lib.name}") },
                    onSelectSeries = { series ->
                        val libraryId = series.libraryId ?: 0
                        nav.navigate("chapters/$libraryId/${series.id}/${series.name}")
                    }
                )
            }

            composable(
                route = "series/{libraryId}/{libraryName}",
                arguments = listOf(
                    navArgument("libraryId") { type = NavType.IntType },
                    navArgument("libraryName") { type = NavType.StringType }
                )
            ) { backStack ->
                val libraryId = backStack.arguments!!.getInt("libraryId")
                val libraryName = backStack.arguments!!.getString("libraryName") ?: ""
                SeriesScreen(sessionStore, libraryId, libraryName) { s ->
                    nav.navigate("chapters/$libraryId/${s.id}/${s.name}")
                }
            }

            composable(
                route = "chapters/{libraryId}/{seriesId}/{seriesName}",
                arguments = listOf(
                    navArgument("libraryId") { type = NavType.IntType },
                    navArgument("seriesId") { type = NavType.IntType },
                    navArgument("seriesName") { type = NavType.StringType }
                )
            ) { backStack ->
                val libraryId = backStack.arguments!!.getInt("libraryId")
                val seriesId = backStack.arguments!!.getInt("seriesId")
                val seriesName = backStack.arguments!!.getString("seriesName") ?: ""
                ChapterPickScreen(sessionStore, libraryId, seriesId, seriesName) { chapterId, volumeId ->
                    nav.navigate("reader/$libraryId/$seriesId/$volumeId/$chapterId")
                }
            }

            composable(
                route = "reader/{libraryId}/{seriesId}/{volumeId}/{chapterId}",
                arguments = listOf(
                    navArgument("libraryId") { type = NavType.IntType },
                    navArgument("seriesId") { type = NavType.IntType },
                    navArgument("volumeId") { type = NavType.IntType },
                    navArgument("chapterId") { type = NavType.IntType }
                )
            ) { backStack ->
                val libraryId = backStack.arguments!!.getInt("libraryId")
                val seriesId = backStack.arguments!!.getInt("seriesId")
                val volumeId = backStack.arguments!!.getInt("volumeId")
                val chapterId = backStack.arguments!!.getInt("chapterId")
                ReaderScreen(
                    sessionStore,
                    settingsStore,
                    libraryId,
                    seriesId,
                    volumeId,
                    chapterId,
                    onBack = { nav.popBackStack() }
                )
            }

            composable("settings") {
                SettingsHubScreen(
                    onServer = { nav.navigate("settings/server") },
                    onReader = { nav.navigate("settings/reader") },
                    onBack = { nav.popBackStack() }
                )
            }

            composable("settings/server") {
                ServerSettingsScreen(
                    sessionStore = sessionStore,
                    onActiveServerChanged = { refreshActiveServer() },
                    onBack = { nav.popBackStack() }
                )
            }
            composable("settings/reader") {
                ReaderSettingsScreen(settingsStore = settingsStore, onBack = { nav.popBackStack() })
            }
        }
    }
}

data class LoginDefaults(
    val baseUrl: String = "",
    val apiKey: String = "",
    val username: String = "",
    val password: String = "",
    val autoLogin: Boolean = false
)

@Composable
fun LoginScreen(
    sessionStore: KavitaSessionStore,
    defaults: LoginDefaults = LoginDefaults(),
    onLoggedIn: suspend () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenServerSettings: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun connectSaved(showMissingAuthError: Boolean = true) {
        busy = true
        error = null
        try {
            val saved = sessionStore.loadDefault()
            if (saved.baseUrl.isBlank() || (saved.jwt.isBlank() && saved.apiKey.isBlank())) {
                if (!showMissingAuthError) return
                error = "No default server auth. Add a server or mark one as default."
                onOpenServerSettings()
                return
            }
            val client = KavitaClient(ctx, sessionStore)
            val (api, _) = client.buildApi()
            api.health()
            onLoggedIn()
        } catch (t: Throwable) {
            error = "Connect failed: ${t.message ?: t.toString()}"
        } finally {
            busy = false
        }
    }

    suspend fun attemptDebugLogin() {
        busy = true
        error = null
        try {
            val cleanBaseUrl = defaults.baseUrl.trim()
            val cleanUsername = defaults.username.trim()
            val cleanApiKey = defaults.apiKey.trim()
            if (cleanBaseUrl.isBlank()) throw IllegalArgumentException("Server URL is required")
            if (cleanApiKey.isBlank()) throw IllegalArgumentException("Auth Key is required for image loading")
            sessionStore.useTransient(
                KavitaSession(baseUrl = cleanBaseUrl, username = cleanUsername, apiKey = "", jwt = "")
            )
            val client = KavitaClient(ctx, sessionStore)
            val (api, _) = client.buildApi()
            val user = api.login(LoginDto(cleanUsername, defaults.password, cleanApiKey.ifBlank { null }))
            val jwt = user.token ?: throw IllegalStateException("No token returned")
            sessionStore.useTransient(
                KavitaSession(baseUrl = cleanBaseUrl, username = cleanUsername, apiKey = cleanApiKey, jwt = "")
            )
            val (apiKeyApi, _) = client.buildApi()
            apiKeyApi.userLibraries()
            sessionStore.save(
                KavitaSession(baseUrl = cleanBaseUrl, username = cleanUsername, apiKey = cleanApiKey, jwt = jwt)
            )
            onLoggedIn()
        } catch (t: Throwable) {
            error = "Debug login failed: ${t.message ?: t.toString()}"
        } finally {
            busy = false
        }
    }

    LaunchedEffect(defaults.autoLogin) {
        if (defaults.autoLogin && !busy) {
            attemptDebugLogin()
        } else if (!busy) {
            connectSaved(showMissingAuthError = false)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Kamigura", style = MaterialTheme.typography.headlineSmall)

            if (error != null) Text(error!!, color = Color.Red)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            connectSaved()
                        }
                    }
                ) { Text(if (busy) "Connecting..." else "Connect") }

                Button(onClick = onOpenSettings) { Text("Settings") }
            }
        }
    }
}
