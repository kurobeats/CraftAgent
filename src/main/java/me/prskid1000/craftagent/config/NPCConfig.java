package me.prskid1000.craftagent.config;

import me.prskid1000.craftagent.constant.Instructions;
import me.prskid1000.craftagent.llm.LLMType;
import java.util.UUID;

import io.wispforest.endec.Endec;
import io.wispforest.endec.StructEndec;
import io.wispforest.endec.impl.StructEndecBuilder;

public class NPCConfig implements Configurable {

	private String npcName = "Steve";
	private UUID uuid = UUID.randomUUID();
	private boolean isActive = true;
	private String customSystemPrompt = ""; // If empty, uses default system prompt
	private String gender = "neutral"; // "male", "female", "neutral"
	private int age = 20; // Age in years
	private long lastAgeUpdateTick = 0; // Last server tick when age was updated
	private LLMType llmType = LLMType.LM_STUDIO;
	private String ollamaUrl = "http://localhost:11434";
    private String llmModel = "nvidia_nemotron-3-nano-30b-a3b";
	private String lmStudioUrl = "http://localhost:1234/v1";
	private String skinUrl = "";
	private boolean skipLLMRequests = false; // If true, skip sending requests to LLM
	private String openRouterApiKey = "";
	private String openRouterModel = "deepseek/deepseek-v4-pro";
	private float openRouterTemperature = 0.7f;
	private int openRouterMaxTokens = 2048;
	private String openRouterApiUrl = "https://openrouter.ai/api/v1/chat/completions";

	public NPCConfig() {}

	public NPCConfig(String npcName) {
		this.npcName = npcName;
	}

    public NPCConfig(
		String npcName,
		String uuid,
		boolean isActive,
		String customSystemPrompt,
		String gender,
		int age,
		long lastAgeUpdateTick,
		LLMType llmType,
        String llmModel,
		String ollamaUrl,
		String lmStudioUrl,
		String skinUrl,
		boolean skipLLMRequests,
		String openRouterApiKey,
		String openRouterModel,
		float openRouterTemperature,
		int openRouterMaxTokens,
		String openRouterApiUrl
	) {
		this.npcName = npcName;
		this.uuid = UUID.fromString(uuid);
		this.isActive = isActive;
		this.customSystemPrompt = customSystemPrompt != null ? customSystemPrompt : "";
		this.gender = gender != null ? gender : "neutral";
		this.age = age;
		this.lastAgeUpdateTick = lastAgeUpdateTick;
		this.llmType = llmType;
        this.llmModel = llmModel;
		this.ollamaUrl = ollamaUrl;
		this.lmStudioUrl = lmStudioUrl;
		this.skinUrl = skinUrl;
		this.skipLLMRequests = skipLLMRequests;
		this.openRouterApiKey = openRouterApiKey != null ? openRouterApiKey : "";
		this.openRouterModel = openRouterModel != null ? openRouterModel : "deepseek/deepseek-v4-pro";
		this.openRouterTemperature = openRouterTemperature;
		this.openRouterMaxTokens = openRouterMaxTokens;
		this.openRouterApiUrl = openRouterApiUrl != null ? openRouterApiUrl : "https://openrouter.ai/api/v1/chat/completions";
	}

	public static class Builder {

		private final NPCConfig npcConfig;

		public Builder(String npcName) {
			this.npcConfig = new NPCConfig(npcName);
		}

		public Builder uuid(UUID uuid) {
			npcConfig.setUuid(uuid);
			return this;
		}


		public Builder llmType(LLMType llmType) {
			npcConfig.setLlmType(llmType);
			return this;
		}

		public Builder skinUrl(String skinUrl) {
			npcConfig.setSkinUrl(skinUrl);
			return this;
		}

		public NPCConfig build() {
			return npcConfig;
		}

	}

	public static Builder builder(String npcName) {
		return new Builder(npcName);
	}

	public String getNpcName() {
		return npcName;
	}

	public boolean isActive() {
		return isActive;
	}

	public String getCustomSystemPrompt() {
		return customSystemPrompt;
	}

	public void setCustomSystemPrompt(String customSystemPrompt) {
		this.customSystemPrompt = customSystemPrompt != null ? customSystemPrompt : "";
	}

	public String getGender() {
		return gender;
	}

	public void setGender(String gender) {
		this.gender = gender != null ? gender : "neutral";
	}

	public int getAge() {
		return age;
	}

	public void setAge(int age) {
		this.age = Math.max(0, age); // Ensure age is non-negative
	}

	public long getLastAgeUpdateTick() {
		return lastAgeUpdateTick;
	}

	public void setLastAgeUpdateTick(long lastAgeUpdateTick) {
		this.lastAgeUpdateTick = lastAgeUpdateTick;
	}

	public LLMType getLlmType() {
		return llmType;
	}

    public String getLlmModel() {
        return llmModel;
    }

    public void setLlmModel(String llmModel) {
        this.llmModel = llmModel;
    }


    public String getOllamaUrl() {
		return ollamaUrl;
	}

	public String getLmStudioUrl() {
		return lmStudioUrl;
	}

	public void setLmStudioUrl(String lmStudioUrl) {
		this.lmStudioUrl = lmStudioUrl;
	}

	public void setLlmType(LLMType llmType) {
		this.llmType = llmType;
	}

	public void setOllamaUrl(String ollamaUrl) {
		this.ollamaUrl = ollamaUrl;
	}

	public void setActive(boolean active) {
		isActive = active;
	}

	public void setNpcName(String npcName) {
		this.npcName = npcName;
	}

	public UUID getUuid() {
		return uuid;
	}

	public void setUuid(UUID uuid) {
		this.uuid = uuid;
	}

	public String getSkinUrl() {
		return skinUrl;
	}

	public void setSkinUrl(String skinUrl) {
		this.skinUrl = skinUrl;
	}

	public boolean isSkipLLMRequests() {
		return skipLLMRequests;
	}

	public void setSkipLLMRequests(boolean skipLLMRequests) {
		this.skipLLMRequests = skipLLMRequests;
	}

	public String getOpenRouterApiKey() {
		return openRouterApiKey;
	}

	public void setOpenRouterApiKey(String openRouterApiKey) {
		this.openRouterApiKey = openRouterApiKey != null ? openRouterApiKey : "";
	}

	public String getOpenRouterModel() {
		return openRouterModel;
	}

	public void setOpenRouterModel(String openRouterModel) {
		this.openRouterModel = openRouterModel != null ? openRouterModel : "deepseek/deepseek-v4-pro";
	}

	public float getOpenRouterTemperature() {
		return openRouterTemperature;
	}

	public void setOpenRouterTemperature(float openRouterTemperature) {
		this.openRouterTemperature = openRouterTemperature;
	}

	public int getOpenRouterMaxTokens() {
		return openRouterMaxTokens;
	}

	public void setOpenRouterMaxTokens(int openRouterMaxTokens) {
		this.openRouterMaxTokens = openRouterMaxTokens;
	}

	public String getOpenRouterApiUrl() {
		return openRouterApiUrl;
	}

	public void setOpenRouterApiUrl(String openRouterApiUrl) {
		this.openRouterApiUrl = openRouterApiUrl != null ? openRouterApiUrl : "https://openrouter.ai/api/v1/chat/completions";
	}

	@Override
	public String getConfigName() {
		return npcName.toLowerCase();
	}


    public static final StructEndec<NPCConfig> ENDEC = StructEndecBuilder.of(
			Endec.STRING.fieldOf("npcName", NPCConfig::getNpcName),
			Endec.STRING.fieldOf("uuid", config -> config.getUuid().toString()),
			Endec.BOOLEAN.fieldOf("isActive", NPCConfig::isActive),
			Endec.STRING.fieldOf("customSystemPrompt", NPCConfig::getCustomSystemPrompt),
			Endec.STRING.fieldOf("gender", NPCConfig::getGender),
			Endec.INT.fieldOf("age", NPCConfig::getAge),
			Endec.LONG.fieldOf("lastAgeUpdateTick", NPCConfig::getLastAgeUpdateTick),
			Endec.forEnum(LLMType.class).fieldOf("llmType", NPCConfig::getLlmType),
			Endec.STRING.fieldOf("llmModel", NPCConfig::getLlmModel),
			Endec.STRING.fieldOf("ollamaUrl", NPCConfig::getOllamaUrl),
			Endec.STRING.fieldOf("lmStudioUrl", NPCConfig::getLmStudioUrl),
			Endec.STRING.fieldOf("skinUrl", NPCConfig::getSkinUrl),
			Endec.BOOLEAN.fieldOf("skipLLMRequests", NPCConfig::isSkipLLMRequests),
			Endec.STRING.fieldOf("openRouterApiKey", NPCConfig::getOpenRouterApiKey),
			Endec.STRING.fieldOf("openRouterModel", NPCConfig::getOpenRouterModel),
			Endec.FLOAT.fieldOf("openRouterTemperature", NPCConfig::getOpenRouterTemperature),
			Endec.INT.fieldOf("openRouterMaxTokens", NPCConfig::getOpenRouterMaxTokens),
			Endec.STRING.fieldOf("openRouterApiUrl", NPCConfig::getOpenRouterApiUrl),
			NPCConfig::new
	);

    public static NPCConfig deepCopy(NPCConfig config) {
        return new NPCConfig(
                config.npcName,
                config.uuid.toString(),
                config.isActive,
                config.customSystemPrompt,
                config.gender,
                config.age,
                config.lastAgeUpdateTick,
                config.llmType,
                config.llmModel,
                config.ollamaUrl,
                config.lmStudioUrl,
                config.skinUrl,
                config.skipLLMRequests,
                config.openRouterApiKey,
                config.openRouterModel,
                config.openRouterTemperature,
                config.openRouterMaxTokens,
                config.openRouterApiUrl
        );
    }

	@Override
	public String toString() {
		return "NPCConfig{npcName=" + npcName +
				",uuid=" + uuid +
				",isActive=" + isActive +
				",llmType=" + llmType +
				",ollamaUrl=" + ollamaUrl + "}";
	}

	//name for fields for npc config screen
	public static final String NPC_NAME = "Name of the NPC";
	public static final String EDIT_NPC = "Edit '%s'";
	public static final String CUSTOM_SYSTEM_PROMPT = "System Instruction";
	public static final String GENDER = "Gender";
	public static final String AGE = "Age (years)";
	public static final String LLM_TYPE = "Type";
    public static final String LLM_MODEL = "LLM Model";
	public static final String OLLAMA_URL = "Ollama URL";
	public static final String LM_STUDIO_URL = "LM Studio URL";
	public static final String OPEN_ROUTER_API_KEY = "OpenRouter API Key";
	public static final String OPEN_ROUTER_MODEL = "OpenRouter Model";
	public static final String OPEN_ROUTER_TEMPERATURE = "Temperature";
	public static final String OPEN_ROUTER_MAX_TOKENS = "Max Tokens";
	public static final String OPEN_ROUTER_API_URL = "API URL";
	public static final String SKIP_LLM_REQUESTS = "Skip LLM Requests";
}
