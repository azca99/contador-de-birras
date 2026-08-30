package com.example.contadordebirras.ui.achievements

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.contadordebirras.domain.achievements.AchievementDifficulty
import com.example.contadordebirras.domain.achievements.AchievementState
import com.example.contadordebirras.domain.achievements.AchievementUiModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AchievementsScreen(
    uiState: AchievementsUiState,
    onBack: () -> Unit
) {
    var selectedCategory by remember { mutableStateOf("Todos") }
    val categories = listOf("Todos") + uiState.achievements.map { it.category }.distinct().sorted()

    Scaffold { padding ->
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                TabRow(selectedTabIndex = 1, containerColor = Color.Transparent, modifier = Modifier.padding(16.dp)) {
                    Tab(selected = false, onClick = onBack, text = { Text("Ajustes", style = MaterialTheme.typography.titleMedium) })
                    Tab(selected = true, onClick = { }, text = { Text("Logros", style = MaterialTheme.typography.titleMedium) })
                }
                
                LevelHeader(
                    userLevel = uiState.userLevel?.name ?: "Recién servido",
                    levelNumber = uiState.userLevel?.level ?: 1,
                    points = uiState.totalPoints,
                    nextLevelPoints = uiState.nextLevelPoints,
                    unlocked = uiState.unlockedCount,
                    total = uiState.totalCount
                )

                ScrollableTabRow(
                    selectedTabIndex = categories.indexOf(selectedCategory).coerceAtLeast(0),
                    edgePadding = 16.dp,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    categories.forEach { category ->
                        Tab(
                            selected = selectedCategory == category,
                            onClick = { selectedCategory = category },
                            text = { Text(category) }
                        )
                    }
                }

                val filteredAchievements = if (selectedCategory == "Todos") {
                    uiState.achievements.filter { !it.isHidden || it.state != AchievementState.LOCKED }
                } else {
                    uiState.achievements.filter { it.category == selectedCategory && (!it.isHidden || it.state != AchievementState.LOCKED) }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredAchievements) { achievement ->
                        AchievementCard(achievement = achievement)
                    }
                }
            }
        }
    }
}

@Composable
fun LevelHeader(
    userLevel: String,
    levelNumber: Int,
    points: Int,
    nextLevelPoints: Int,
    unlocked: Int,
    total: Int
) {
    val progress = if (nextLevelPoints > 0) points.toFloat() / nextLevelPoints else 1f
    val animatedProgress by animateFloatAsState(targetValue = progress.coerceIn(0f, 1f))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.EmojiEvents,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Nivel $levelNumber: $userLevel",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = "$points PTS",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Black
            )
            Spacer(modifier = Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = animatedProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "$unlocked / $total logros",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = "Próximo: $nextLevelPoints",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
fun AchievementCard(achievement: AchievementUiModel) {
    val isLocked = achievement.state == AchievementState.LOCKED
    val alpha = if (isLocked) 0.5f else 1f
    
    val diffColor = when (achievement.difficulty) {
        AchievementDifficulty.COMUN -> Color(0xFF9E9E9E)
        AchievementDifficulty.POCO_COMUN -> Color(0xFF4CAF50)
        AchievementDifficulty.RARO -> Color(0xFF2196F3)
        AchievementDifficulty.EPICO -> Color(0xFF9C27B0)
        AchievementDifficulty.LEGENDARIO -> Color(0xFFFFC107)
        AchievementDifficulty.RESPONSABLE -> Color(0xFF00BCD4)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isLocked) 0.dp else 4.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(if (isLocked) MaterialTheme.colorScheme.surfaceVariant else diffColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                if (isLocked) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Bloqueado",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.EmojiEvents, // TODO: Use specific icon based on iconKey
                        contentDescription = achievement.name,
                        tint = diffColor
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = achievement.name,
                    style = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)),
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = achievement.description,
                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
                )
                
                if (achievement.state == AchievementState.IN_PROGRESS && achievement.target > 1) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = achievement.progressPercent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                        color = diffColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Text(
                        text = "${achievement.currentProgress} / ${achievement.target}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${achievement.points}",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (isLocked) MaterialTheme.colorScheme.onSurfaceVariant else diffColor,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = "PTS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
                )
            }
        }
    }
}
