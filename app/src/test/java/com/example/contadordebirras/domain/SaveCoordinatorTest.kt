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
            val success = coordinator.executeSave(saveAction = {
                delay(1000)
                executionCount++
            })
            assertTrue(success)
        }
        
        advanceTimeBy(100)
        assertTrue(coordinator.isSaving.value)
        
        val job2 = launch {
            val success2 = coordinator.executeSave(saveAction = {
                executionCount++
            })
            assertFalse(success2) // Was blocked
        }
        
        advanceUntilIdle()
        
        assertFalse(coordinator.isSaving.value)
        assertEquals(1, executionCount)
    }

    @Test
    fun `Can save again after completion`() = runTest {
        val coordinator = SaveCoordinator()
        var executionCount = 0
        
        val r1 = coordinator.executeSave(saveAction = { executionCount++ })
        assertTrue(r1)
        assertFalse(coordinator.isSaving.value)
        
        val r2 = coordinator.executeSave(saveAction = { executionCount++ })
        assertTrue(r2)
        assertEquals(2, executionCount)
    }

    @Test
    fun `Releases lock on exception and reports failure`() = runTest {
        val coordinator = SaveCoordinator()
        
        val result = coordinator.executeSave(saveAction = { throw RuntimeException("Error") })
        
        assertFalse(result)
        assertFalse(coordinator.isSaving.value)
        
        // Permite reintentar
        var executionCount = 0
        val r2 = coordinator.executeSave(saveAction = { executionCount++ })
        assertTrue(r2)
        assertEquals(1, executionCount)
    }
}
