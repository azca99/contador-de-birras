package com.example.contadordebirras.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.contadordebirras.data.UserRepository
import com.example.contadordebirras.domain.AuthRepository
import com.example.contadordebirras.domain.BeerRepository
import com.example.contadordebirras.domain.FriendsRepository
import com.example.contadordebirras.ui.main.MainViewModel
import com.example.contadordebirras.ui.stats.StatsViewModel
import com.example.contadordebirras.ui.profile.ProfileViewModel
import com.example.contadordebirras.ui.friends.FriendsViewModel
import com.example.contadordebirras.ui.groups.GroupsViewModel
import com.example.contadordebirras.ui.groups.GroupDetailViewModel
import com.example.contadordebirras.domain.GroupsRepository
import com.example.contadordebirras.data.achievements.AchievementRepository
import com.example.contadordebirras.ui.achievements.AchievementsViewModel

class AppViewModelFactory(
    private val beerRepository: BeerRepository,
    private val userRepository: UserRepository,
    private val authRepository: AuthRepository,
    private val friendsRepository: FriendsRepository,
    private val groupsRepository: GroupsRepository,
    private val achievementRepository: AchievementRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(beerRepository, userRepository) as T
        }
        if (modelClass.isAssignableFrom(StatsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return StatsViewModel(beerRepository) as T
        }
        if (modelClass.isAssignableFrom(ProfileViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ProfileViewModel(userRepository, authRepository) as T
        }
        if (modelClass.isAssignableFrom(FriendsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return FriendsViewModel(friendsRepository) as T
        }
        if (modelClass.isAssignableFrom(GroupsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return GroupsViewModel(groupsRepository) as T
        }
        if (modelClass.isAssignableFrom(GroupDetailViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return GroupDetailViewModel(groupsRepository, friendsRepository) as T
        }
        if (modelClass.isAssignableFrom(AchievementsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AchievementsViewModel(beerRepository, achievementRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
