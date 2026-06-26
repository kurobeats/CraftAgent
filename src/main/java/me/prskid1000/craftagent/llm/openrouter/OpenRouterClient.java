package me.prskid1000.craftagent.llm.openrouter;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.prskid1000.craftagent.exception.CraftAgentException;
import me.prskid1000.craftagent.util.LogUtil;
import me.prskid1000.craftagent.history.ConversationMessage;
import me.prskid1000.craftagent.llm.LLMClient;
import me.prskid1000.craftagent.llm.LLMResponse;
import me.prskid1000.craftagent.llm.LLMSchema;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenRouter LLM client implementation.
 * Uses the OpenAI-compatible Chat Completions API via OpenRouter.
 * Supports any model available through OpenRouter (DeepSeek, Claude, GPT, etc.).
 * 
 * API Endpoint: https://openrouter.ai/api/v1/chat/completions
 * Auth: Bearer token via Authorization header
 */
public class OpenRouterClient implements LLMClient {

    private final String model;
    private final String apiKey;
    private final String apiUrl;
    private final float temperature;
    private final int maxTokens;
    private volatile int timeout;
    private volatile HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * Constructor for OpenRouterClient.
     *
     * @param model       the model name (e.g. "deepseek/deepseek-v4-pro")
     * @param apiKey      the OpenRouter API key
     * @param apiUrl      the API endpoint URL
     * @param temperature the temperature for generation (0.0 - 2.0)
     * @param maxTokens   the maximum tokens in the response
     * @param timeout     the HTTP request timeout in seconds
     */
    public OpenRouterClient(
            String model,
            String apiKey,
            String apiUrl,
            float temperature,
            int maxTokens,
            int timeout
    ) {
        this.model = model;
        this.apiKey = apiKey;
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.timeout = timeout;

        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.httpClient = createHttpClient(timeout);
    }

    /**
     * Updates the timeout value and recreates the HTTP client in real-time.
     */
    public void updateTimeout(int newTimeout) {
        this.timeout = newTimeout;
        this.httpClient = createHttpClient(newTimeout);
    }

    private HttpClient createHttpClient(int timeoutSeconds) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    /**
     * Check if the OpenRouter service is reachable and the API key is valid.
     * Uses a lightweight request to validate connectivity and authentication.
     *
     * @throws CraftAgentException if the service is not reachable or the API key is invalid
     */
    @Override
    public void checkServiceIsReachable() {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw CraftAgentException.llmService("OpenRouter API key is not configured. Please set 'openrouter.api_key' in your NPC config.");
        }

        try {
            // Use a minimal request to validate API key and connectivity
            Map<String, Object> testBody = new HashMap<>();
            testBody.put("model", model);
            testBody.put("messages", List.of(
                    Map.of("role", "user", "content", "ping")
            ));
            testBody.put("max_tokens", 1);
            testBody.put("stream", false);

            String requestBodyJson = objectMapper.writeValueAsString(testBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("HTTP-Referer", "https://github.com/prskid1000/CraftAgent")
                    .header("X-Title", "CraftAgent Minecraft Mod")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                    .timeout(Duration.ofSeconds(timeout))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();

            switch (statusCode) {
                case 200:
                    // Success - API is reachable and key is valid
                    LogUtil.debugInChat("OpenRouter connection validated successfully");
                    return;
                case 401:
                    throw CraftAgentException.llmService("OpenRouter API key is invalid or expired. Please check your 'openrouter.api_key' configuration.");
                case 403:
                    throw CraftAgentException.llmService("OpenRouter access forbidden. Your API key may not have access to this model: " + model);
                case 429:
                    throw CraftAgentException.llmService("OpenRouter rate limit exceeded. Please wait and try again.");
                default:
                    throw CraftAgentException.llmService("OpenRouter health check returned unexpected status: " + statusCode + ". Response: " + response.body());
            }
        } catch (CraftAgentException e) {
            throw e;
        } catch (Exception e) {
            LogUtil.error("OpenRouter connection error", e);
            throw CraftAgentException.llmService("OpenRouter service is not reachable at: " + apiUrl + ". Error: " + e.getMessage(), e);
        }
    }

    @Override
    public LLMResponse chat(List<ConversationMessage> messages, net.minecraft.server.MinecraftServer server) {
        // Validate API key before making request
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw CraftAgentException.llmService("OpenRouter API key is missing. Please configure 'openrouter.api_key' in your NPC settings.");
        }

        try {
            // Convert messages to OpenAI Chat Completions format
            List<Map<String, String>> openaiMessages = new ArrayList<>();
            for (ConversationMessage msg : messages) {
                Map<String, String> openaiMsg = new HashMap<>();
                openaiMsg.put("role", msg.getRole());
                openaiMsg.put("content", msg.getMessage());
                openaiMessages.add(openaiMsg);
            }

            // Build the request body per OpenAI Chat Completions schema
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", openaiMessages);
            requestBody.put("stream", false);
            requestBody.put("temperature", temperature);
            requestBody.put("max_tokens", maxTokens);

            // Add response_format for structured output (JSON schema)
            Map<String, Object> responseFormat = new HashMap<>();
            responseFormat.put("type", "json_schema");
            Map<String, Object> jsonSchema = new HashMap<>();
            jsonSchema.put("name", "message_schema");
            jsonSchema.put("schema", LLMSchema.getMessageSchema());
            jsonSchema.put("strict", true);
            responseFormat.put("json_schema", jsonSchema);
            requestBody.put("response_format", responseFormat);

            String requestBodyJson = objectMapper.writeValueAsString(requestBody);

            LogUtil.debugInChat("LLM Request sent to OpenRouter (model: " + model + ")");

            // Build HTTP request with OpenRouter-specific headers
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("HTTP-Referer", "https://github.com/prskid1000/CraftAgent")
                    .header("X-Title", "CraftAgent Minecraft Mod")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                    .timeout(Duration.ofSeconds(timeout))
                    .build();

            // Send request and get response
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            LogUtil.debugInChat("LLM Response received from OpenRouter (status: " + response.statusCode() + ")");

            // Handle HTTP error responses with specific error messages
            int statusCode = response.statusCode();
            if (statusCode != 200) {
                String errorMessage = parseOpenRouterError(statusCode, response.body());
                throw CraftAgentException.llmService(errorMessage);
            }

            // Parse the OpenAI-compatible response
            Map<String, Object> responseMap = objectMapper.readValue(response.body(), Map.class);

            // Check for API-level errors in the response body
            if (responseMap.containsKey("error")) {
                Map<String, Object> errorObj = (Map<String, Object>) responseMap.get("error");
                String errorMsg = errorObj != null ? String.valueOf(errorObj.getOrDefault("message", "Unknown OpenRouter error")) : "Unknown OpenRouter error";
                throw CraftAgentException.llmService("OpenRouter API error: " + errorMsg);
            }

            // Extract choices[0].message.content
            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw CraftAgentException.llmService("OpenRouter API returned no choices in response. Response: " + response.body());
            }

            Map<String, Object> firstChoice = choices.get(0);

            // Check finish_reason for errors
            String finishReason = (String) firstChoice.get("finish_reason");
            if (finishReason != null) {
                switch (finishReason) {
                    case "length":
                        LogUtil.debugInChat("OpenRouter response truncated due to max_tokens limit");
                        break;
                    case "content_filter":
                        throw CraftAgentException.llmService("OpenRouter response was filtered by content policy");
                    case "error":
                        throw CraftAgentException.llmService("OpenRouter generation failed with error");
                }
            }

            Map<String, Object> messageMap = (Map<String, Object>) firstChoice.get("message");
            if (messageMap == null) {
                throw CraftAgentException.llmService("OpenRouter API response missing 'message' field in choices[0]");
            }

            String content = (String) messageMap.get("content");
            if (content == null || content.trim().isEmpty()) {
                LogUtil.debugInChat("OpenRouter returned empty content - model may have refused to generate");
                return new LLMResponse("{\"message\":\"\",\"actions\":[]}");
            }

            return new LLMResponse(content);

        } catch (CraftAgentException e) {
            throw e;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            LogUtil.error("Failed to parse OpenRouter JSON response", e);
            throw CraftAgentException.llmService("Failed to parse OpenRouter response as JSON. The model may have returned invalid JSON. Error: " + e.getMessage(), e);
        } catch (java.net.http.HttpTimeoutException e) {
            throw CraftAgentException.llmService("OpenRouter request timed out after " + timeout + " seconds. Try increasing the timeout or check your network connection.", e);
        } catch (java.net.ConnectException e) {
            throw CraftAgentException.llmService("Cannot connect to OpenRouter at: " + apiUrl + ". Check your network connection.", e);
        } catch (Exception e) {
            String lastPrompt = messages.isEmpty() ? "(no messages)" :
                    messages.get(messages.size() - 1).getMessage();
            // Truncate long prompts in error messages
            if (lastPrompt.length() > 100) {
                lastPrompt = lastPrompt.substring(0, 100) + "...";
            }
            throw CraftAgentException.llmService("Could not generate response from OpenRouter. Model: " + model + ". Error: " + e.getMessage(), e);
        }
    }

    /**
     * Parses HTTP error responses from OpenRouter and returns user-friendly error messages.
     *
     * @param statusCode the HTTP status code
     * @param responseBody the response body (may contain JSON error details)
     * @return a human-readable error message
     */
    private String parseOpenRouterError(int statusCode, String responseBody) {
        // Try to extract error message from JSON response body
        String apiErrorMessage = "";
        try {
            Map<String, Object> errorResponse = objectMapper.readValue(responseBody, Map.class);
            if (errorResponse.containsKey("error")) {
                Object errorObj = errorResponse.get("error");
                if (errorObj instanceof Map) {
                    Map<String, Object> errorMap = (Map<String, Object>) errorObj;
                    apiErrorMessage = " - " + errorMap.getOrDefault("message", "");
                } else if (errorObj instanceof String) {
                    apiErrorMessage = " - " + errorObj;
                }
            }
        } catch (Exception ignored) {
            // If we can't parse the error JSON, just use the status code
        }

        switch (statusCode) {
            case 400:
                return "OpenRouter: Bad request (400). The model '" + model + "' may not exist or parameters are invalid." + apiErrorMessage;
            case 401:
                return "OpenRouter: Unauthorized (401). Your API key is invalid or expired. Please check your 'openrouter.api_key' configuration." + apiErrorMessage;
            case 402:
                return "OpenRouter: Payment required (402). Your OpenRouter account may have insufficient credits." + apiErrorMessage;
            case 403:
                return "OpenRouter: Forbidden (403). Your API key does not have access to model: " + model + apiErrorMessage;
            case 404:
                return "OpenRouter: Not found (404). The endpoint or model '" + model + "' was not found." + apiErrorMessage;
            case 408:
                return "OpenRouter: Request timeout (408). The server took too long to respond.";
            case 429:
                return "OpenRouter: Rate limited (429). Too many requests. Please wait before retrying." + apiErrorMessage;
            case 500:
                return "OpenRouter: Internal server error (500). The OpenRouter service is experiencing issues. Please try again later." + apiErrorMessage;
            case 502:
                return "OpenRouter: Bad gateway (502). The upstream model provider may be unavailable." + apiErrorMessage;
            case 503:
                return "OpenRouter: Service unavailable (503). OpenRouter may be temporarily down for maintenance." + apiErrorMessage;
            default:
                if (statusCode >= 500) {
                    return "OpenRouter: Server error (" + statusCode + ")." + apiErrorMessage;
                }
                return "OpenRouter: Unexpected response (" + statusCode + ")." + apiErrorMessage;
        }
    }

    @Override
    public void stopService() {
        // Nothing to stop for OpenRouter - no persistent connections
    }
}
