package me.prskid1000.craftagent.common

import me.prskid1000.craftagent.config.ConfigProvider
import me.prskid1000.craftagent.config.NPCConfig
import me.prskid1000.craftagent.constant.Instructions
import me.prskid1000.craftagent.context.ContextProvider
import me.prskid1000.craftagent.database.repositories.ConversationRepository
import me.prskid1000.craftagent.database.repositories.PrivateBookPageRepository
import me.prskid1000.craftagent.database.repositories.MessageRepository
import me.prskid1000.craftagent.database.repositories.SharebookRepository
import me.prskid1000.craftagent.event.NPCEventHandler
import me.prskid1000.craftagent.exception.CraftAgentException
import me.prskid1000.craftagent.history.ConversationHistory
import me.prskid1000.craftagent.history.ConversationMessage
import me.prskid1000.craftagent.llm.LLMClient
import me.prskid1000.craftagent.llm.LLMType
import me.prskid1000.craftagent.llm.ollama.OllamaClient
import me.prskid1000.craftagent.llm.lmstudio.LMStudioClient
import me.prskid1000.craftagent.llm.openrouter.OpenRouterClient
import me.prskid1000.craftagent.memory.MemoryManager
import me.prskid1000.craftagent.model.NPC
import net.minecraft.server.network.ServerPlayerEntity

class NPCFactory(
    private val configProvider: ConfigProvider,
    private val conversationRepository: ConversationRepository,
    private val privateBookPageRepository: PrivateBookPageRepository,
    private val messageRepository: MessageRepository,
    private val sharebookRepository: SharebookRepository,
    private var npcService: NPCService?
) {
    fun setNpcService(service: NPCService) {
        this.npcService = service
    }
     fun createNpc(npcEntity: ServerPlayerEntity, config: NPCConfig): NPC {
        val baseConfig = configProvider.baseConfig
        val contextProvider = ContextProvider(npcEntity, baseConfig)
        
        // Create memory manager
        val memoryManager = MemoryManager(privateBookPageRepository, config.uuid, baseConfig)
        contextProvider.memoryManager = memoryManager
        
        // Set repositories for mail and sharebook
        contextProvider.setRepositories(messageRepository, sharebookRepository, config.uuid)

        val llmClient = initLLMClient(config)

        // System prompt is generated fresh, never stored in database
        // Create a function that generates the system prompt on demand
        val systemPromptGenerator: () -> String = {
            Instructions.getLlmSystemPrompt(
                config.npcName,
                config.age,
                config.gender,
                "", // Commands are in tool definition now, not in prompt
                config.customSystemPrompt,
                config.llmType,
                npcEntity.server,
                baseConfig
            )
        }

        val history = ConversationHistory(
            llmClient,
            conversationRepository,
            config.uuid,
            systemPromptGenerator,
            baseConfig.conversationHistoryLength
        )
        val eventHandler = NPCEventHandler(llmClient, history, contextProvider, config, messageRepository, sharebookRepository, npcService!!)
        return NPC(npcEntity, llmClient, history, eventHandler, contextProvider, config)
    }

    private fun initLLMClient(config: NPCConfig): LLMClient {
        val baseConfig = configProvider.baseConfig
        val llmClient = when (config.llmType) {
            LLMType.OLLAMA -> {
                OllamaClient(config.llmModel, config.ollamaUrl, baseConfig.llmTimeout, baseConfig.isVerbose)
            }
            LLMType.LM_STUDIO -> {
                LMStudioClient(config.llmModel, config.lmStudioUrl, baseConfig.llmTimeout)
            }
            LLMType.OPEN_ROUTER -> {
                OpenRouterClient(
                    config.openRouterModel,
                    config.openRouterApiKey,
                    config.openRouterApiUrl,
                    config.openRouterTemperature,
                    config.openRouterMaxTokens,
                    baseConfig.llmTimeout
                )
            }
            else -> throw CraftAgentException.npcCreation("Invalid LLM type: ${config.llmType}")
        }
        // Note: Health check is done in NPCService.createNpc() before spawning to avoid blocking server thread
        return llmClient
    }
    
    /**
     * Check if the LLM service is reachable. This should be called on a background thread,
     * not on the Minecraft server thread.
     */
    fun checkLLMServiceReachable(config: NPCConfig) {
        val baseConfig = configProvider.baseConfig
        val llmClient = when (config.llmType) {
            LLMType.OLLAMA -> {
                OllamaClient(config.llmModel, config.ollamaUrl, baseConfig.llmTimeout, baseConfig.isVerbose)
            }
            LLMType.LM_STUDIO -> {
                LMStudioClient(config.llmModel, config.lmStudioUrl, baseConfig.llmTimeout)
            }
            LLMType.OPEN_ROUTER -> {
                OpenRouterClient(
                    config.openRouterModel,
                    config.openRouterApiKey,
                    config.openRouterApiUrl,
                    config.openRouterTemperature,
                    config.openRouterMaxTokens,
                    baseConfig.llmTimeout
                )
            }
            else -> throw CraftAgentException.npcCreation("Invalid LLM type: ${config.llmType}")
        }
        llmClient.checkServiceIsReachable()
    }

}
