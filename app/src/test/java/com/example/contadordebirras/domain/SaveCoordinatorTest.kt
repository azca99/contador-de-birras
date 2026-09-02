package com.example.contadordebirras.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class SaveCoordinatorTest {

    @Test
    fun `Double tap is ignored`() = runTest {
        val coordinator = SaveCoordinator()
        var executionCount = 0
        
        val job1 = launch {
            coordinator.executeSave(saveAction = {
                delay(1000)
                executionCount++
            }, onComplete = {})
        }
        
        advanceTimeBy(100)
        assertTrue(coordinator.isSaving.value)
        
        val job2 = launch {
            coordinator.executeSave(saveAction = {
                executionCount++
            }, onComplete = {})
        }
        
        advanceUntilIdle()
        
        assertFalse(coordinator.isSaving.value)
        assertEquals(1, executionCount)
    }

    @Test
    fun `Can save again after completion`() = runTest {
        val coordinator = SaveCoordinator()
        var executionCount = 0
        
        coordinator.executeSave(saveAction = { executionCount++ }, onComplete = {})
        assertFalse(coordinator.isSaving.value)
        
        coordinator.executeSave(saveAction = { executionCount++ }, onComplete = {})
        assertEquals(2, executionCount)
    }

    @Test
    fun `Releases lock on exception`() = runTest {
        val coordinator = SaveCoordinator()
        
        try {
            coordinator.executeSave(saveAction = { throw RuntimeException("Error") }, onComplete = {})
        } catch (e: Exception) {}
        
        assertFalse(coordinator.isSaving.value)
    }
}
