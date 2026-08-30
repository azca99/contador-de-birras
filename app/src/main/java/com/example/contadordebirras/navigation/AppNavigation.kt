package com.example.contadordebirras.navigation


import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.example.contadordebirras.ui.AppViewModelFactory
import com.example.contadordebirras.ui.history.HistoryScreen
import com.example.contadordebirras.ui.main.MainScreen
import com.example.contadordebirras.ui.profile.ProfileScreen
import com.example.contadordebirras.ui.stats.StatsScreen
import com.example.contadordebirras.ui.stats.StatsViewModel
import com.example.contadordebirras.ui.friends.FriendsScreen
import com.example.contadordebirras.ui.friends.FriendDetailScreen
import com.example.contadordebirras.ui.friends.FriendsViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Groups
import com.example.contadordebirras.ui.groups.GroupsScreen
import com.example.contadordebirras.ui.groups.GroupDetailScreen
import com.example.contadordebirras.ui.groups.GroupsViewModel
import com.example.contadordebirras.ui.groups.GroupDetailViewModel
import com.example.contadordebirras.ui.achievements.AchievementsScreen
import com.example.contadordebirras.ui.achievements.AchievementsViewModel

@Composable
fun AppNavigation(factory: AppViewModelFactory) {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                val colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )

                NavigationBarItem(
                    colors = colors,
                    icon = { Icon(Icons.Rounded.Home, contentDescription = "Inicio") },
                    label = { Text("Inicio") },
                    selected = currentRoute == "main",
                    onClick = { navController.navigate("main") { launchSingleTop = true } }
                )
                NavigationBarItem(
                    colors = colors,
                    icon = { Icon(Icons.Rounded.List, contentDescription = "Historial") },
                    label = { Text("Historial") },
                    selected = currentRoute == "history" || currentRoute == "stats",
                    onClick = { navController.navigate("history") { launchSingleTop = true } }
                )
                NavigationBarItem(
                    colors = colors,
                    icon = { Icon(Icons.Rounded.Groups, contentDescription = "Comunidad") },
                    label = { Text("Comunidad") },
                    selected = currentRoute == "friends" || currentRoute?.startsWith("friend_detail") == true || currentRoute == "groups" || currentRoute?.startsWith("group_detail") == true,
                    onClick = { navController.navigate("groups") { launchSingleTop = true } }
                )
                NavigationBarItem(
                    colors = colors,
                    icon = { Icon(Icons.Rounded.Person, contentDescription = "Perfil") },
                    label = { Text("Perfil") },
                    selected = currentRoute == "profile" || currentRoute == "achievements",
                    onClick = { navController.navigate("profile") { launchSingleTop = true } }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            MetalTableBackground()
            NavHost(
                navController = navController,
                startDestination = "main"
            ) {
                composable("main") {
                    MainScreen(viewModel(factory = factory))
                }
                composable("stats") {
                    StatsScreen(viewModel(factory = factory))
                }
                composable("history") {
                    HistoryScreen(
                        viewModel = viewModel<StatsViewModel>(factory = factory),
                        onStatsClick = { navController.navigate("stats") }
                    )
                }
                composable("profile") {
                    ProfileScreen(
                        viewModel = viewModel(factory = factory),
                        onAchievementsClick = { navController.navigate("achievements") }
                    )
                }
                composable("friends") {
                    FriendsScreen(
                        viewModel = viewModel(factory = factory),
                        onFriendClick = { uid, alias -> 
                            navController.navigate("friend_detail/$uid/$alias") 
                        },
                        onGroupsClick = { navController.navigate("groups") }
                    )
                }
                composable(
                    route = "friend_detail/{uid}/{alias}",
                    arguments = listOf(
                        androidx.navigation.navArgument("uid") { type = androidx.navigation.NavType.StringType },
                        androidx.navigation.navArgument("alias") { type = androidx.navigation.NavType.StringType }
                    )
                ) { backStackEntry ->
                    val uid = backStackEntry.arguments?.getString("uid") ?: ""
                    val alias = backStackEntry.arguments?.getString("alias") ?: ""
                    FriendDetailScreen(
                        viewModel = viewModel<FriendsViewModel>(factory = factory),
                        friendUid = uid,
                        friendAlias = alias,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable("groups") {
                    GroupsScreen(
                        viewModel = viewModel(factory = factory),
                        onGroupClick = { groupId ->
                            navController.navigate("group_detail/$groupId")
                        },
                        onFriendsClick = { navController.navigate("friends") { launchSingleTop = true } }
                    )
                }
                composable(
                    route = "group_detail/{groupId}",
                    arguments = listOf(
                        androidx.navigation.navArgument("groupId") { type = androidx.navigation.NavType.StringType }
                    )
                ) { backStackEntry ->
                    val groupId = backStackEntry.arguments?.getString("groupId") ?: ""
                    GroupDetailScreen(
                        groupId = groupId,
                        viewModel = viewModel<GroupDetailViewModel>(factory = factory),
                        onBack = { navController.popBackStack() }
                    )
                }
                composable("achievements") {
                    AchievementsScreen(
                        uiState = viewModel<AchievementsViewModel>(factory = factory).uiState.collectAsState().value,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
            
            val achievementsViewModel = viewModel<AchievementsViewModel>(factory = factory)
            AchievementNotificationOverlay(achievementsViewModel)
        }
    }
}

@Composable
fun AchievementNotificationOverlay(viewModel: AchievementsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var currentUnlock by remember { mutableStateOf<com.example.contadordebirras.domain.achievements.AchievementUiModel?>(null) }
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.newUnlocksEvent.collect { unlocks ->
            if (unlocks.isNotEmpty()) {
                currentUnlock = unlocks.first()
                isVisible = true
                kotlinx.coroutines.delay(4000)
                isVisible = false
            }
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = Modifier.padding(16.dp)
    ) {
        currentUnlock?.let { ach ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp),
                elevation = CardDefaults.cardElevation(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🏆", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.width(8.dp))
                        androidx.compose.foundation.layout.Column {
                            Text("¡Logro Desbloqueado!", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                            Text(ach.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    uiState.userLevel?.let { level ->
                        val progress = if (uiState.nextLevelPoints > 0) uiState.totalPoints.toFloat() / uiState.nextLevelPoints else 1f
                        Text("Nivel ${level.level}: ${level.name}", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(
                            progress = progress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text("${uiState.totalPoints} / ${uiState.nextLevelPoints} pts", style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.End))
                    }
                }
            }
        }
    }
}

@Composable
fun MetalTableBackground() {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val bgColor = if (isDark) androidx.compose.ui.graphics.Color(0xFF1E1E1E) else androidx.compose.ui.graphics.Color(0xFFE5E5E5)
    val ringColor = if (isDark) androidx.compose.ui.graphics.Color(0xFF333333).copy(alpha = 0.6f) else androidx.compose.ui.graphics.Color(0xFFFFFFFF).copy(alpha = 0.6f)
    val innerColor = if (isDark) androidx.compose.ui.graphics.Color(0xFF2A2A2A).copy(alpha = 0.4f) else androidx.compose.ui.graphics.Color(0xFFCCCCCC).copy(alpha = 0.4f)

    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize().background(bgColor)) {
        val radius = 24.dp.toPx()
        val spacing = radius * 1.2f
        
        var y = -radius
        var row = 0
        while (y < size.height + radius) {
            var x = if (row % 2 == 0) -radius else spacing / 2 - radius
            while (x < size.width + radius) {
                // Outer subtle ring
                drawCircle(
                    color = ringColor,
                    radius = radius,
                    center = androidx.compose.ui.geometry.Offset(x, y),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                )
                // Inner metallic reflection
                drawCircle(
                    color = innerColor,
                    radius = radius * 0.8f,
                    center = androidx.compose.ui.geometry.Offset(x, y)
                )
                x += spacing
            }
            y += spacing * 0.866f // hexagonal grid
            row++
        }
    }
}
