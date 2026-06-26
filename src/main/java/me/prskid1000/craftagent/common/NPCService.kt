package me.prskid1000.craftagent.common

import me.prskid1000.craftagent.auth.UsernameValidator
import me.prskid1000.craftagent.callback.NPCEvents
import me.prskid1000.craftagent.config.BaseConfig
import me.prskid1000.craftagent.config.ConfigProvider
import me.prskid1000.craftagent.config.NPCConfig
import me.prskid1000.craftagent.constant.Instructions
import me.prskid1000.craftagent.coordination.CoordinationService
import me.prskid1000.craftagent.database.resources.ResourceProvider
import me.prskid1000.craftagent.exception.CraftAgentException
import me.prskid1000.craftagent.model.NPC
import me.prskid1000.craftagent.util.LogUtil
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.server.MinecraftServer
import net.minecraft.server.PlayerManager
import net.minecraft.util.math.BlockPos
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.jvm.JvmField

class NPCService(
    private val factory: NPCFactory,
    private val configProvider: ConfigProvider,
    private val resourceProvider: ResourceProvider
) {
    @JvmField
    var webServer: me.prskid1000.craftagent.web.WebServer? = null
    val coordinationService = CoordinationService(this)

    private lateinit var executorService: ExecutorService
    val uuidToNpc = ConcurrentHashMap<UUID, NPC>()
    private val entityUuidToConfigUuid = ConcurrentHashMap<UUID, UUID>()
    private var deathEventRegistered = false
    
    /**
     * Get all active NPCs for web server access.
     */
    fun getAllNPCs(): Collection<NPC> {
        return uuidToNpc.values
    }
    
    /**
     * Get NPC by UUID for web server access.
     */
    fun getNPC(uuid: UUID): NPC? {
        return uuidToNpc[uuid]
    }
    
    /**
     * Saves an NPC config to disk immediately.
     * Exposed for use by listeners and other components that update configs.
     */
    fun saveNpcConfig(config: me.prskid1000.craftagent.config.NPCConfig) {
        configProvider.saveNpcConfig(config)
    }

    /**
     * Sends a message from a player to an NPC via mail system.
     * Messages are stored in mail and can be read by the NPC later.
     */
    fun sendPlayerMessageToNpc(playerUuid: UUID, playerName: String, npcUuid: UUID, messageContent: String) {
        val messageRepository = resourceProvider.messageRepository ?: return
        
        val message = me.prskid1000.craftagent.model.database.Message(
            recipientUuid = npcUuid,
            senderUuid = playerUuid,
            senderName = playerName,
            senderType = "PLAYER",
            content = messageContent
        )
        
        val maxMessages = configProvider.baseConfig.getMaxMessages()
        messageRepository.insert(message, maxMessages)
        
        // Message is stored in mail system and will be available in context during next LLM call
        // Note: Don't display in chat again - the original player message is already visible
        // Note: Not adding to conversation history - mail is accessed via context instead
    }

    fun init(server: MinecraftServer) {
        executorService = Executors.newSingleThreadExecutor()
        registerDeathEvent()
        respawnActiveNPCs(server)
    }

    private fun registerDeathEvent() {
        if (!deathEventRegistered) {
            NPCEvents.ON_DEATH.register { entity ->
                val configUuid = entityUuidToConfigUuid[entity.uuid]
                val deadNpc = uuidToNpc.values.firstOrNull { it.entity.uuid == entity.uuid }
                if (configUuid != null) {
                    val server = entity.getWorld().server
                    removeNpc(configUuid, server.playerManager)
                } else if (deadNpc != null) {
                    val server = entity.getWorld().server
                    removeNpc(deadNpc.config.uuid, server.playerManager)
                }
            }
            deathEventRegistered = true
        }
    }

    private fun respawnActiveNPCs(server: MinecraftServer) {
        configProvider.getNpcConfigs().forEach { config ->
            if (config.isActive && !uuidToNpc.containsKey(config.uuid)) {
				try {
					createNpc(config, server, null, null)
				} catch (e: Exception) {
					LogUtil.error("Failed to respawn NPC: ${config.npcName}", e)
				}
            }
        }
    }

    fun createNpc(newConfig: NPCConfig, server: MinecraftServer, spawnPos: BlockPos?, owner: PlayerEntity?) {
        CompletableFuture.runAsync({
            val name = newConfig.npcName
            checkNpcName(name)

            val config = updateConfig(newConfig)
            
            // Check LLM service reachability BEFORE spawning entity (on background thread, not server thread)
            try {
                factory.checkLLMServiceReachable(config)
            } catch (e: Exception) {
                LogUtil.error("LLM service is not reachable for NPC: $name", e)
                LogUtil.errorInChat("Failed to create NPC '$name': LLM service is not reachable. ${e.message}")
                val npcConfig = configProvider.getNpcConfig(config.uuid)
                if (npcConfig.isPresent) {
                    npcConfig.get().isActive = false
                }
                return@runAsync
            }

            NPCSpawner.spawn(config, server, spawnPos) { npcEntity ->
                server.execute {
                    try {
                        config.uuid = npcEntity.uuid
                        // Initialize age update tick if not set
                        if (config.lastAgeUpdateTick == 0L) {
                            config.setLastAgeUpdateTick(server.overworld.time)
                        }
                        
                        // Update config in provider with the new UUID
                        configProvider.updateNpcConfig(config)
                        
                        // Save config to disk immediately so it persists across restarts
                        configProvider.saveNpcConfig(config)
                        
                        val npc = factory.createNpc(npcEntity, config)
                        // Owner is now stored in config, no need to set on controller
                        uuidToNpc[config.uuid] = npc
                        entityUuidToConfigUuid[npcEntity.uuid] = config.uuid

                        LogUtil.infoInChat("Added NPC with name: $name")
                    } catch (e: Exception) {
                        LogUtil.error("Error creating NPC: $name", e)
                        LogUtil.errorInChat("Failed to initialize NPC: ${e.message}")
                        NPCSpawner.remove(npcEntity.uuid, server.playerManager)
                        val npcConfig = configProvider.getNpcConfig(config.uuid)
                        if (npcConfig.isPresent) {
                            npcConfig.get().isActive = false
                        }
                    }
                }
            }
        }, executorService).exceptionally {
            LogUtil.errorInChat(it.message)
            LogUtil.error(it)
            null
        }
    }

    fun removeNpc(uuid: UUID, playerManager: PlayerManager) {
        val npcToRemove = uuidToNpc[uuid]
        if (npcToRemove != null) {
            val server = playerManager.server
            val entityUuid = npcToRemove.entity.uuid
            
            server.execute {
                try {
                    // Controller removed, no stop needed
                    npcToRemove.llmClient.stopService()
                    npcToRemove.eventHandler.stopService()
                    npcToRemove.contextProvider.chunkManager.stopService()
                    // Conversations are already saved in database, no need to save again
                    uuidToNpc.remove(uuid)
                    entityUuidToConfigUuid.remove(entityUuid)

                    NPCSpawner.remove(entityUuid, playerManager)

                    val config = configProvider.getNpcConfig(uuid)
                    val npcName = if (config.isPresent) config.get().npcName else "Unknown"
                    
                    if (config.isPresent) {
                        config.get().isActive = false
                        // Save config immediately so the change persists
                        configProvider.saveNpcConfig(config.get())
                        LogUtil.infoInChat("Removed NPC with name $npcName")
                    } else {
                        LogUtil.infoInChat("Removed NPC with uuid $uuid")
                    }
                    
                    // Check if this was the last NPC - if so, clear shared knowledge
                    // Check after removing from map to get accurate count
                    val remainingNpcCount = uuidToNpc.size
                    if (remainingNpcCount == 0) {
                        // Last NPC being removed - clear all shared knowledge on background thread
                        CompletableFuture.runAsync({
                            try {
                                resourceProvider.sharebookRepository?.deleteAll()
                            } catch (e: Exception) {
                                LogUtil.error("Error clearing shared knowledge", e)
                            }
                        }, executorService)
                    }
                } catch (e: Exception) {
                    LogUtil.error("Error removing NPC: $uuid", e)
                }
            }
            
            // Database operations on background thread (blocking I/O)
            // Delete all associated data for removed NPC
            CompletableFuture.runAsync({
                try {
                    // Delete conversations
                    resourceProvider.conversationRepository.deleteByUuid(uuid)
                    // Delete private book pages
                    resourceProvider.privateBookPageRepository?.deleteByNpcUuid(uuid)
                    // Delete messages where NPC is sender or recipient
                    resourceProvider.messageRepository?.deleteByNpcUuid(uuid)
                    // Note: Sharebook is global/shared knowledge - cleared only when NPC count becomes 0
                } catch (e: Exception) {
                    LogUtil.error("Error deleting data for removed NPC: $uuid", e)
                }
            }, executorService)
        }
    }

    fun deleteNpc(uuid: UUID, playerManager: PlayerManager) {
        val npcToDelete = uuidToNpc[uuid]
        if (npcToDelete != null) {
            val server = playerManager.server
            val entityUuid = npcToDelete.entity.uuid
            
            // Stop services and remove from maps on server thread (thread-safe operations only)
            server.execute {
                try {
                    // Controller removed, no stop needed
                    npcToDelete.llmClient.stopService()
                    npcToDelete.eventHandler.stopService()
                    npcToDelete.contextProvider.chunkManager.stopService()
                    // Memory is stored directly in database, no cleanup needed
                    
                    // Conversations are stored in database, no need to remove from memory
                    uuidToNpc.remove(uuid)
                    entityUuidToConfigUuid.remove(entityUuid)
                    
                    NPCSpawner.remove(entityUuid, playerManager)
                    
                    LogUtil.infoInChat("Deleted NPC with uuid $uuid")
                    
                    // Check if this was the last NPC - if so, clear shared knowledge
                    // Check after removing from map to get accurate count
                    val remainingNpcCount = uuidToNpc.size
                    if (remainingNpcCount == 0) {
                        // Last NPC being deleted - clear all shared knowledge on background thread
                        CompletableFuture.runAsync({
                            try {
                                resourceProvider.sharebookRepository?.deleteAll()
                            } catch (e: Exception) {
                                LogUtil.error("Error clearing shared knowledge", e)
                            }
                        }, executorService)
                    }
                } catch (e: Exception) {
                    LogUtil.error("Error deleting NPC: $uuid", e)
                }
            }
            
            // Database operations on background thread (blocking I/O)
            CompletableFuture.runAsync({
                try {
                    resourceProvider.conversationRepository.deleteByUuid(uuid)
                    // Delete private book pages for this NPC
                    resourceProvider.privateBookPageRepository?.deleteByNpcUuid(uuid)
                    // Delete messages where NPC is sender or recipient
                    resourceProvider.messageRepository?.deleteByNpcUuid(uuid)
                    // Note: Sharebook is global/shared knowledge - cleared only when NPC count becomes 0
                    
                    configProvider.deleteNpcConfig(uuid)
                } catch (e: Exception) {
                    LogUtil.error("Error deleting NPC data from database: $uuid", e)
                }
            }, executorService)
        } else {
            // NPC not in map, clean up data on background thread
            CompletableFuture.runAsync({
                try {
                    // Conversations are stored in database, no need to remove from memory
                    resourceProvider.conversationRepository.deleteByUuid(uuid)
                    // Delete private book pages for this NPC
                    resourceProvider.privateBookPageRepository?.deleteByNpcUuid(uuid)
                    // Delete messages where NPC is sender or recipient
                    resourceProvider.messageRepository?.deleteByNpcUuid(uuid)
                    // Note: Sharebook is global/shared knowledge - cleared only when NPC count becomes 0
                    // Check is done in server.execute block above
                    
                    configProvider.deleteNpcConfig(uuid)
                } catch (e: Exception) {
                    LogUtil.error("Error cleaning up NPC data: $uuid", e)
                }
            }, executorService)
        }
    }

    fun shutdownNPCs(server: MinecraftServer) {
        uuidToNpc.keys.forEach {
            shutdownNpc(it, server.playerManager)
        }
        if (::executorService.isInitialized) {
            executorService.shutdown()
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow()
                }
            } catch (e: InterruptedException) {
                executorService.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }
    }
    
    /**
     * Shuts down an NPC without marking it as inactive.
     * Used during server shutdown to preserve isActive state for respawning on restart.
     */
    private fun shutdownNpc(uuid: UUID, playerManager: PlayerManager) {
        val npcToShutdown = uuidToNpc[uuid]
        if (npcToShutdown != null) {
            val server = playerManager.server
            val entityUuid = npcToShutdown.entity.uuid
            
            server.execute {
                try {
                    // Stop services
                    npcToShutdown.llmClient.stopService()
                    npcToShutdown.eventHandler.stopService()
                    npcToShutdown.contextProvider.chunkManager.stopService()
                    
                    // Conversations are already saved in database, no need to save again
                    
                    // Remove from maps
                    uuidToNpc.remove(uuid)
                    entityUuidToConfigUuid.remove(entityUuid)

                    // Remove entity from world
                    NPCSpawner.remove(entityUuid, playerManager)
                    
                    // Note: We do NOT set isActive = false here, so NPCs can respawn on restart
                } catch (e: Exception) {
                    LogUtil.error("Error shutting down NPC: $uuid", e)
                }
            }
        }
    }

    private fun updateConfig(newConfig: NPCConfig): NPCConfig {
        val existingConfig = configProvider.getNpcConfigByName(newConfig.npcName)
        if (existingConfig.isEmpty) {
            return configProvider.addNpcConfig(newConfig)
        } else {
            // Update existing config with new values (preserve UUID)
            val configToUpdate = existingConfig.get()
            val originalUuid = configToUpdate.uuid
            // Copy all fields from newConfig to existing config
            configToUpdate.npcName = newConfig.npcName
            configToUpdate.isActive = true
            configToUpdate.customSystemPrompt = newConfig.customSystemPrompt
            configToUpdate.gender = newConfig.gender
            configToUpdate.age = newConfig.age
            configToUpdate.lastAgeUpdateTick = newConfig.lastAgeUpdateTick
            configToUpdate.llmType = newConfig.llmType
            configToUpdate.llmModel = newConfig.llmModel
            configToUpdate.ollamaUrl = newConfig.ollamaUrl
            configToUpdate.lmStudioUrl = newConfig.lmStudioUrl
            configToUpdate.skinUrl = newConfig.skinUrl
            configToUpdate.openRouterApiKey = newConfig.openRouterApiKey
            configToUpdate.openRouterModel = newConfig.openRouterModel
            configToUpdate.openRouterTemperature = newConfig.openRouterTemperature
            configToUpdate.openRouterMaxTokens = newConfig.openRouterMaxTokens
            configToUpdate.openRouterApiUrl = newConfig.openRouterApiUrl
            // Preserve original UUID
            configToUpdate.uuid = originalUuid
            configProvider.updateNpcConfig(configToUpdate)
            return configToUpdate
        }
    }

    private fun checkNpcName(npcName: String) {
        if (!UsernameValidator.isValid(npcName)) {
            throw CraftAgentException.npcCreation("NPC name is not valid. Use 3–16 characters: letters, numbers, or underscores only.")
        } else if (uuidToNpc.values.any { it.entity.name.string == npcName }) {
            throw CraftAgentException.npcCreation("A NPC with the name '$npcName' already exists.")
        }
    }

    /**
     * Updates the system prompt for an active NPC dynamically at runtime.
     * Uses custom system prompt if provided, otherwise rebuilds from config.
     */
    fun updateNpcSystemPrompt(npcUuid: UUID) {
        // System prompt is generated fresh from Instructions.java, no need to update
        // The prompt will be regenerated on next LLM call
    }

    /**
     * Updates the system prompt for an NPC with a custom prompt string.
     * This allows runtime modification of the system prompt.
     * Note: System prompts are now generated fresh, not stored.
     */
    fun updateNpcSystemPrompt(npcUuid: UUID, customSystemPrompt: String) {
        // System prompt is generated fresh, not stored
        // Custom prompts are stored in NPC config and will be included when generating fresh prompt
        val npc = uuidToNpc[npcUuid]
        if (npc != null) {
            // Update the config's custom system prompt
            npc.config.customSystemPrompt = customSystemPrompt
        }
    }

    /**
     * Updates all active NPCs with new base config values in real-time.
     * This is called when base config is updated from the GUI.
     * Reinitializes components that depend on base config (LLM clients, ChunkManager, etc.)
     */
    fun updateAllNpcsWithBaseConfig(newBaseConfig: BaseConfig) {
        uuidToNpc.forEach { (uuid, npc) ->
            try {
                // Update LLM client timeout if it supports it
                updateLLMClientTimeout(npc, newBaseConfig.getLlmTimeout())
                
                // Update ChunkManager with new config values
                npc.contextProvider.chunkManager.updateConfig(
                    newBaseConfig.getContextChunkRadius(),
                    newBaseConfig.getContextVerticalScanRange(),
                    newBaseConfig.getMaxNearbyBlocks(),
                    newBaseConfig.getChunkExpiryTime()
                )
            } catch (e: Exception) {
                LogUtil.error("Error updating NPC ${npc.config.npcName} with base config", e)
            }
        }
    }
    
    /**
     * Updates LLM client timeout in real-time.
     * Reinitializes HTTP client if needed.
     */
    private fun updateLLMClientTimeout(npc: NPC, newTimeout: Int) {
        when (val client = npc.llmClient) {
            is me.prskid1000.craftagent.llm.ollama.OllamaClient -> {
                // Reinitialize with new timeout
                client.updateTimeout(newTimeout)
            }
            is me.prskid1000.craftagent.llm.lmstudio.LMStudioClient -> {
                // Reinitialize with new timeout
                client.updateTimeout(newTimeout)
            }
            is me.prskid1000.craftagent.llm.openrouter.OpenRouterClient -> {
                // Reinitialize with new timeout
                client.updateTimeout(newTimeout)
            }
        }
    }

}