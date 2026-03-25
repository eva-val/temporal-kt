package com.surrealdev.temporal.application.worker

import com.surrealdev.temporal.application.plugin.HookRegistry
import com.surrealdev.temporal.application.plugin.hooks.SlotSupplierMetricsContext
import com.surrealdev.temporal.application.plugin.hooks.SlotSupplierMetricsSampled
import com.surrealdev.temporal.core.SlotSupplier
import com.surrealdev.temporal.core.internal.CustomSlotSupplierBridge
import com.surrealdev.temporal.core.internal.JvmResourceMonitor
import com.surrealdev.temporal.core.internal.PidController
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Runs the grant loop for a [SlotSupplier.JvmResourceBased] slot supplier.
 *
 * This is a suspend function launched as a child coroutine of the [ManagedWorker] scope,
 * following the same structured concurrency pattern as the workflow/activity polling loops.
 * It participates in the worker's lifecycle — canceled when the worker shuts down.
 *
 * On each tick:
 * 1. Samples JVM memory and CPU via [JvmResourceMonitor]
 * 2. Updates PID controllers and publishes decision to the FFI bridge
 * 3. Fires the [SlotSupplierMetricsSampled] hook for observability
 * 4. Attempts to grant one pending slot reservation via the bridge
 */
internal suspend fun runSlotSupplierGrantLoop(
    bridge: CustomSlotSupplierBridge,
    config: SlotSupplier.JvmResourceBased,
    monitor: JvmResourceMonitor,
    slotType: String,
    taskQueue: String,
    hookRegistry: HookRegistry,
) {
    val tuning = config.pidTuning
    val memoryPid =
        PidController(
            setpoint = config.targetMemoryUsage,
            kp = tuning.memoryPGain,
            ki = tuning.memoryIGain,
            kd = tuning.memoryDGain,
        )
    val cpuPid =
        PidController(
            setpoint = config.targetCpuUsage,
            kp = tuning.cpuPGain,
            ki = tuning.cpuIGain,
            kd = tuning.cpuDGain,
        )

    while (currentCoroutineContext().isActive) {
        try {
            val memUsage = monitor.memoryUsage()
            val cpuLoad = monitor.cpuLoad()

            // Update PID controllers
            val memOutput = memoryPid.update(memUsage)
            val cpuOutput = cpuPid.update(cpuLoad)

            // PID + hard ceiling (matches Core SDK's can_reserve check)
            val pidDecision =
                memUsage <= config.targetMemoryUsage &&
                    memOutput > tuning.memoryOutputThreshold &&
                    cpuOutput > tuning.cpuOutputThreshold
            bridge.pidGrantDecision = pidDecision

            // Fire metrics hook
            hookRegistry.callBlocking(
                SlotSupplierMetricsSampled,
                SlotSupplierMetricsContext(
                    taskQueue = taskQueue,
                    slotType = slotType,
                    memoryUsage = memUsage,
                    cpuLoad = cpuLoad,
                    memoryPidOutput = memOutput,
                    cpuPidOutput = cpuOutput,
                    activeSlots = bridge.getActiveSlotCount(),
                    pendingReserves = bridge.getPendingCount(),
                ),
            )

            // Try to grant one pending reservation
            if (bridge.getPendingCount() > 0) {
                val canGrant =
                    bridge.getActiveSlotCount() < config.minimumSlots || pidDecision
                if (canGrant) {
                    val claim = bridge.tryClaimNextPending()
                    if (claim != null) {
                        val accepted = bridge.completeGrant(claim.completionCtx, claim.permitId)
                        if (!accepted) {
                            bridge.cancelGrant(claim.completionCtx)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Don't let a single tick failure kill the loop
            if (e is kotlinx.coroutines.CancellationException) throw e
        }

        delay(config.rampThrottleMs)
    }
}
