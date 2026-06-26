package me.prskid1000.craftagent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import me.prskid1000.craftagent.common.NPCService;
import me.prskid1000.craftagent.config.ConfigProvider;
import me.prskid1000.craftagent.llm.StructuredLLMResponse;
import me.prskid1000.craftagent.model.NPC;
import me.prskid1000.craftagent.util.LogUtil;
import net.minecraft.server.MinecraftServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

/**
 * Web server for viewing NPC properties, context, messages, and mail in a browser.
 * Accessible at http://localhost:8080
 */
public class WebServer {
    private static final int PORT = 8080;
    private HttpServer server;
    private final NPCService npcService;
    private final ConfigProvider configProvider;
    private final ObjectMapper objectMapper;
    
    public WebServer(NPCService npcService, ConfigProvider configProvider) {
        this.npcService = npcService;
        this.configProvider = configProvider;
        this.objectMapper = new ObjectMapper()
                .configure(SerializationFeature.INDENT_OUTPUT, true);
    }
    
    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(PORT), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            
            // API endpoints - order matters! More specific paths first
            server.createContext("/api/npcs", this::handleGetNPCs);
            server.createContext("/api/npc/", this::handleNPCRequest);
            server.createContext("/api/config", this::handleGetConfig);
            
            // Static files
            server.createContext("/", this::handleStatic);
            
            server.start();
        } catch (IOException e) {
            LogUtil.error("Failed to start web server", e);
        }
    }
    
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }
    
    private void handleGetNPCs(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }
        
        List<Map<String, Object>> npcs = npcService.getAllNPCs().stream()
                .map(this::npcToMap)
                .collect(Collectors.toList());
        
        sendJsonResponse(exchange, 200, npcs);
    }
    
    private void handleGetConfig(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }
        
        var baseConfig = configProvider.getBaseConfig();
        Map<String, Object> config = new HashMap<>();
        config.put("llmProcessingInterval", baseConfig.getLlmProcessingInterval());
        config.put("llmMinInterval", baseConfig.getLlmMinInterval());
        // Use max of both intervals for refresh rate
        config.put("refreshInterval", Math.max(baseConfig.getLlmProcessingInterval(), baseConfig.getLlmMinInterval()));
        
        sendJsonResponse(exchange, 200, config);
    }
    
    private void handleNPCRequest(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }
        
        String path = exchange.getRequestURI().getPath();
        String[] parts = path.split("/");
        
        if (parts.length < 4) {
            sendError(exchange, 400, "Invalid NPC UUID");
            return;
        }
        
        String uuidStr = parts[3];
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, "Invalid UUID format");
            return;
        }
        
        NPC npc = npcService.getNPC(uuid);
        if (npc == null) {
            sendError(exchange, 404, "NPC not found");
            return;
        }
        
        // Route to specific handler based on path
        if (parts.length == 4) {
            // /api/npc/{uuid} - Get NPC details
            Map<String, Object> npcData = npcToDetailedMap(npc);
            sendJsonResponse(exchange, 200, npcData);
        } else if (parts.length == 5) {
            String endpoint = parts[4];
            switch (endpoint) {
                case "context":
                    handleGetNPCContext(npc, exchange);
                    break;
                case "state":
                    handleGetNPCState(npc, exchange);
                    break;
                case "messages":
                    handleGetNPCMessages(npc, exchange);
                    break;
                case "mail":
                    handleGetNPCMail(npc, uuid, exchange);
                    break;
                case "memory":
                    handleGetNPCMemory(npc, exchange);
                    break;
                default:
                    sendError(exchange, 404, "Unknown endpoint: " + endpoint);
            }
        } else {
            sendError(exchange, 400, "Invalid path");
        }
    }
    
    private void handleGetNPCContext(NPC npc, HttpExchange exchange) throws IOException {
        try {
            var context = npc.getContextProvider().buildContext();
            Map<String, Object> contextMap = contextToMap(context);
            sendJsonResponse(exchange, 200, contextMap);
        } catch (Exception e) {
            LogUtil.error("Error getting NPC context", e);
            sendError(exchange, 500, "Error getting context: " + e.getMessage());
        }
    }
    
    private void handleGetNPCState(NPC npc, HttpExchange exchange) throws IOException {
        try {
            var context = npc.getContextProvider().buildContext();
            Map<String, Object> state = new HashMap<>();
            Map<String, Object> position = new HashMap<>();
            position.put("x", context.state().position().getX());
            position.put("y", context.state().position().getY());
            position.put("z", context.state().position().getZ());
            state.put("position", position);
            state.put("health", context.state().health());
            state.put("food", context.state().food());
            state.put("biome", context.state().biome());
            sendJsonResponse(exchange, 200, state);
        } catch (Exception e) {
            LogUtil.error("Error getting NPC state", e);
            sendError(exchange, 500, "Error getting state: " + e.getMessage());
        }
    }
    
    private void handleGetNPCMessages(NPC npc, HttpExchange exchange) throws IOException {
        try {
            var history = npc.getHistory().getLatestConversations();
            List<Map<String, Object>> messages = new ArrayList<>();
            
            // Add system prompt first (generated fresh, never stored)
            Map<String, Object> systemMessageMap = new HashMap<>();
            systemMessageMap.put("role", "system");
            systemMessageMap.put("timestamp", 0L);
            systemMessageMap.put("content", npc.getHistory().getSystemPrompt());
            systemMessageMap.put("message", npc.getHistory().getSystemPrompt());
            systemMessageMap.put("actions", Collections.emptyList());
            messages.add(systemMessageMap);
            
            // Add all other messages (user/assistant only)
            for (var msg : history) {
                Map<String, Object> messageMap = new HashMap<>();
                messageMap.put("role", msg.getRole());
                messageMap.put("timestamp", msg.getTimestamp());
                
                // Parse message based on role
                String role = msg.getRole();
                switch (role) {
                    case "assistant" -> {
                        String content = msg.getMessage();
                        StructuredLLMResponse structured = StructuredLLMResponse.parse(content);
                        messageMap.put("content", structured.getMessage());
                        messageMap.put("message", structured.getMessage());
                        messageMap.put("actions", structured.getActions());
                        messageMap.put("rawContent", content);
                    }
                    default -> {
                        messageMap.put("content", msg.getMessage());
                        messageMap.put("message", msg.getMessage());
                        messageMap.put("actions", Collections.emptyList());
                    }
                }
                
                messages.add(messageMap);
            }
            sendJsonResponse(exchange, 200, messages);
        } catch (Exception e) {
            LogUtil.error("Error getting NPC messages", e);
            sendError(exchange, 500, "Error getting messages: " + e.getMessage());
        }
    }
    
    private void handleGetNPCMail(NPC npc, UUID uuid, HttpExchange exchange) throws IOException {
        try {
            var messageRepo = npc.getContextProvider().getMessageRepository();
            if (messageRepo == null) {
                sendJsonResponse(exchange, 200, Collections.emptyList());
                return;
            }
            
            var messages = messageRepo.selectByRecipient(uuid, 100);
            List<Map<String, Object>> mailList = new ArrayList<>();
            for (var msg : messages) {
                Map<String, Object> mailMap = new HashMap<>();
                mailMap.put("id", msg.getId());
                mailMap.put("senderName", msg.getSenderName());
                mailMap.put("content", msg.getContent());
                mailMap.put("timestamp", msg.getTimestamp());
                // No read/unread concept - all mail shown is "new" until processed by LLM
                mailList.add(mailMap);
            }
            sendJsonResponse(exchange, 200, mailList);
        } catch (Exception e) {
            LogUtil.error("Error getting NPC mail", e);
            sendError(exchange, 500, "Error getting mail: " + e.getMessage());
        }
    }
    
    private void handleGetNPCMemory(NPC npc, HttpExchange exchange) throws IOException {
        try {
            var contextProvider = npc.getContextProvider();
            var memoryManager = contextProvider.memoryManager;
            
            Map<String, Object> memory = new HashMap<>();
            
            // Add private book pages
            List<Map<String, Object>> privatePages = new ArrayList<>();
            if (memoryManager != null) {
                for (var page : memoryManager.getPages()) {
                    Map<String, Object> pageMap = new HashMap<>();
                    pageMap.put("pageTitle", page.getPageTitle());
                    pageMap.put("content", page.getContent());
                    pageMap.put("timestamp", page.getTimestamp());
                    privatePages.add(pageMap);
                }
            }
            memory.put("privateBook", privatePages);
            
            // Add sharebook (shared information accessible to all NPCs)
            List<Map<String, Object>> sharebookPages = new ArrayList<>();
            var sharebookRepository = contextProvider.getSharebookRepository();
            if (sharebookRepository != null) {
                for (var page : sharebookRepository.selectAll()) {
                    Map<String, Object> pageMap = new HashMap<>();
                    pageMap.put("pageTitle", page.getPageTitle());
                    pageMap.put("content", page.getContent());
                    pageMap.put("authorUuid", page.getAuthorUuid());
                    pageMap.put("timestamp", page.getTimestamp());
                    sharebookPages.add(pageMap);
                }
            }
            memory.put("sharebook", sharebookPages);
            
            sendJsonResponse(exchange, 200, memory);
        } catch (Exception e) {
            LogUtil.error("Error getting NPC memory", e);
            sendError(exchange, 500, "Error getting memory: " + e.getMessage());
        }
    }
    
    
    
    private void handleStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/") || path.equals("/index.html")) {
            path = "/index.html";
        }
        
        // Remove leading slash for file path
        String filePath = path.startsWith("/") ? path.substring(1) : path;
        
        // Try to load from resources first, then fallback to embedded HTML
        InputStream resourceStream = getClass().getClassLoader().getResourceAsStream("web/" + filePath);
        
        if (resourceStream == null && filePath.equals("index.html")) {
            // Serve embedded HTML if file not found
            String html = getEmbeddedHTML();
            sendHtmlResponse(exchange, 200, html);
            return;
        }
        
        if (resourceStream == null) {
            sendError(exchange, 404, "File not found");
            return;
        }
        
        String contentType = getContentType(filePath);
        byte[] content = resourceStream.readAllBytes();
        resourceStream.close();
        
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(content);
        }
    }
    
    private String getContentType(String filePath) {
        if (filePath.endsWith(".html")) return "text/html; charset=utf-8";
        if (filePath.endsWith(".css")) return "text/css; charset=utf-8";
        if (filePath.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (filePath.endsWith(".json")) return "application/json; charset=utf-8";
        return "text/plain; charset=utf-8";
    }
    
    private Map<String, Object> npcToMap(NPC npc) {
        Map<String, Object> map = new HashMap<>();
        map.put("uuid", npc.getConfig().getUuid().toString());
        map.put("name", npc.getConfig().getNpcName());
        map.put("age", npc.getConfig().getAge());
        map.put("gender", npc.getConfig().getGender());
        map.put("isActive", npc.getConfig().isActive());
        map.put("llmType", npc.getConfig().getLlmType().toString());
        
        Map<String, Object> position = new HashMap<>();
        position.put("x", npc.getEntity().getX());
        position.put("y", npc.getEntity().getY());
        position.put("z", npc.getEntity().getZ());
        map.put("position", position);
        
        map.put("health", npc.getEntity().getHealth());
        map.put("food", npc.getEntity().getHungerManager().getFoodLevel());
        return map;
    }
    
    private Map<String, Object> npcToDetailedMap(NPC npc) {
        Map<String, Object> map = new HashMap<>(npcToMap(npc));
        map.put("entityUuid", npc.getEntity().getUuid().toString());
        map.put("llmModel", npc.getConfig().getLlmModel());
        map.put("ollamaUrl", npc.getConfig().getOllamaUrl());
        map.put("lmStudioUrl", npc.getConfig().getLmStudioUrl());
        map.put("openRouterModel", npc.getConfig().getOpenRouterModel());
        map.put("openRouterApiUrl", npc.getConfig().getOpenRouterApiUrl());
        return map;
    }
    
    private Map<String, Object> contextToMap(me.prskid1000.craftagent.model.context.WorldContext context) {
        Map<String, Object> map = new HashMap<>();
        
        Map<String, Object> state = new HashMap<>();
        Map<String, Object> position = new HashMap<>();
        position.put("x", context.state().position().getX());
        position.put("y", context.state().position().getY());
        position.put("z", context.state().position().getZ());
        state.put("position", position);
        state.put("health", context.state().health());
        state.put("food", context.state().food());
        state.put("biome", context.state().biome());
        map.put("state", state);
        
        Map<String, Object> inventory = new HashMap<>();
        inventory.put("hotbar", context.inventory().hotbar().size());
        inventory.put("mainInventory", context.inventory().mainInventory().size());
        inventory.put("armor", context.inventory().armor().size());
        inventory.put("offHand", context.inventory().offHand().size());
        map.put("inventory", inventory);
        
        // Convert nearbyBlocks to detailed list
        List<Map<String, Object>> blocksList = new ArrayList<>();
        for (var block : context.nearbyBlocks()) {
            Map<String, Object> blockMap = new HashMap<>();
            blockMap.put("type", block.type());
            Map<String, Object> blockPos = new HashMap<>();
            blockPos.put("x", block.position().getX());
            blockPos.put("y", block.position().getY());
            blockPos.put("z", block.position().getZ());
            blockMap.put("position", blockPos);
            blockMap.put("mineLevel", block.mineLevel());
            blockMap.put("toolNeeded", block.toolNeeded());
            blocksList.add(blockMap);
        }
        map.put("nearbyBlocks", blocksList);
        
        // Convert nearbyEntities to detailed list
        List<Map<String, Object>> entitiesList = new ArrayList<>();
        for (var entity : context.nearbyEntities()) {
            Map<String, Object> entityMap = new HashMap<>();
            entityMap.put("id", entity.id());
            entityMap.put("name", entity.name());
            entityMap.put("isPlayer", entity.isPlayer());
            entityMap.put("type", entity.isPlayer() ? "Player" : "Entity");
            entitiesList.add(entityMap);
        }
        map.put("nearbyEntities", entitiesList);
        
        if (context.memoryData() != null) {
            map.put("memory", context.memoryData());
        }
        
        // Add navigation data
        if (context.navigation() != null) {
            Map<String, Object> navigation = new HashMap<>();
            navigation.put("state", context.navigation().state());
            navigation.put("stateDescription", context.navigation().stateDescription());
            navigation.put("timeInCurrentState", context.navigation().timeInCurrentState());
            if (context.navigation().destination() != null) {
                Map<String, Object> dest = new HashMap<>();
                dest.put("x", context.navigation().destination().getX());
                dest.put("y", context.navigation().destination().getY());
                dest.put("z", context.navigation().destination().getZ());
                navigation.put("destination", dest);
            }
            map.put("navigation", navigation);
        }
        
        // Add line of sight data
        if (context.lineOfSight() != null) {
            Map<String, Object> lineOfSight = new HashMap<>();
            
            // Items in line of sight
            List<Map<String, Object>> itemsList = new ArrayList<>();
            for (var item : context.lineOfSight().items()) {
                Map<String, Object> itemMap = new HashMap<>();
                itemMap.put("type", item.type());
                itemMap.put("count", item.count());
                itemMap.put("distance", item.distance());
                Map<String, Object> itemPos = new HashMap<>();
                itemPos.put("x", item.position().getX());
                itemPos.put("y", item.position().getY());
                itemPos.put("z", item.position().getZ());
                itemMap.put("position", itemPos);
                itemsList.add(itemMap);
            }
            lineOfSight.put("items", itemsList);
            
            // Entities in line of sight
            List<Map<String, Object>> losEntitiesList = new ArrayList<>();
            for (var entity : context.lineOfSight().entities()) {
                Map<String, Object> entityMap = new HashMap<>();
                entityMap.put("id", entity.id());
                entityMap.put("name", entity.name());
                entityMap.put("isPlayer", entity.isPlayer());
                losEntitiesList.add(entityMap);
            }
            lineOfSight.put("entities", losEntitiesList);
            
            // Target block (where NPC is looking)
            if (context.lineOfSight().targetBlock() != null) {
                Map<String, Object> targetBlock = new HashMap<>();
                targetBlock.put("type", context.lineOfSight().targetBlock().type());
                Map<String, Object> blockPos = new HashMap<>();
                blockPos.put("x", context.lineOfSight().targetBlock().position().getX());
                blockPos.put("y", context.lineOfSight().targetBlock().position().getY());
                blockPos.put("z", context.lineOfSight().targetBlock().position().getZ());
                targetBlock.put("position", blockPos);
                targetBlock.put("mineLevel", context.lineOfSight().targetBlock().mineLevel());
                targetBlock.put("toolNeeded", context.lineOfSight().targetBlock().toolNeeded());
                lineOfSight.put("targetBlock", targetBlock);
            }
            
            // Visible blocks
            List<Map<String, Object>> visibleBlocksList = new ArrayList<>();
            for (var block : context.lineOfSight().visibleBlocks()) {
                Map<String, Object> blockMap = new HashMap<>();
                blockMap.put("type", block.type());
                Map<String, Object> blockPos = new HashMap<>();
                blockPos.put("x", block.position().getX());
                blockPos.put("y", block.position().getY());
                blockPos.put("z", block.position().getZ());
                blockMap.put("position", blockPos);
                blockMap.put("mineLevel", block.mineLevel());
                blockMap.put("toolNeeded", block.toolNeeded());
                visibleBlocksList.add(blockMap);
            }
            lineOfSight.put("visibleBlocks", visibleBlocksList);
            
            map.put("lineOfSight", lineOfSight);
        }
        
        // Add action state data
        if (context.actionState() != null) {
            Map<String, Object> actionState = new HashMap<>();
            actionState.put("actionType", context.actionState().actionType());
            actionState.put("actionDescription", context.actionState().actionDescription());
            actionState.put("timeInCurrentAction", context.actionState().timeInCurrentAction());
            if (context.actionState().actionData() != null) {
                actionState.put("actionData", context.actionState().actionData());
            }
            map.put("actionState", actionState);
        }
        
        return map;
    }
    
    private void sendJsonResponse(HttpExchange exchange, int statusCode, Object data) throws IOException {
        String json = objectMapper.writeValueAsString(data);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(statusCode, json.getBytes(StandardCharsets.UTF_8).length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
    }
    
    private void sendHtmlResponse(HttpExchange exchange, int statusCode, String html) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, html.getBytes(StandardCharsets.UTF_8).length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(html.getBytes(StandardCharsets.UTF_8));
        }
    }
    
    private void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        Map<String, String> error = Map.of("error", message);
        sendJsonResponse(exchange, statusCode, error);
    }
    
    
    private String getEmbeddedHTML() {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>CraftAgent - NPC Dashboard</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            background-attachment: fixed;
            min-height: 100vh;
            padding: 20px;
            color: #333;
            animation: gradientShift 15s ease infinite;
        }
        @keyframes gradientShift {
            0%, 100% { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); }
            50% { background: linear-gradient(135deg, #764ba2 0%, #667eea 100%); }
        }
        .container {
            max-width: 1400px;
            margin: 0 auto;
        }
        header {
            background: rgba(255, 255, 255, 0.95);
            backdrop-filter: blur(10px);
            padding: 30px;
            border-radius: 20px;
            margin-bottom: 30px;
            box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
            border: 1px solid rgba(255, 255, 255, 0.2);
            animation: slideDown 0.5s ease-out;
        }
        @keyframes slideDown {
            from { opacity: 0; transform: translateY(-20px); }
            to { opacity: 1; transform: translateY(0); }
        }
        h1 {
            color: #667eea;
            font-size: 2.5em;
            margin-bottom: 10px;
        }
        .subtitle {
            color: #666;
            font-size: 1.1em;
        }
        .npcs-grid {
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(350px, 1fr));
            gap: 20px;
            margin-bottom: 20px;
        }
        .npc-card {
            background: rgba(255, 255, 255, 0.95);
            backdrop-filter: blur(10px);
            border-radius: 20px;
            padding: 25px;
            box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
            border: 1px solid rgba(255, 255, 255, 0.2);
            cursor: pointer;
            transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
            animation: fadeInUp 0.5s ease-out;
            animation-fill-mode: both;
        }
        .npc-card:nth-child(1) { animation-delay: 0.1s; }
        .npc-card:nth-child(2) { animation-delay: 0.2s; }
        .npc-card:nth-child(3) { animation-delay: 0.3s; }
        .npc-card:nth-child(4) { animation-delay: 0.4s; }
        .npc-card:nth-child(5) { animation-delay: 0.5s; }
        @keyframes fadeInUp {
            from { opacity: 0; transform: translateY(20px); }
            to { opacity: 1; transform: translateY(0); }
        }
        .npc-card:hover {
            transform: translateY(-8px) scale(1.02);
            box-shadow: 0 16px 48px rgba(102, 126, 234, 0.3);
        }
        .npc-header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            margin-bottom: 15px;
            padding-bottom: 15px;
            border-bottom: 2px solid #f0f0f0;
        }
        .npc-name {
            font-size: 1.5em;
            font-weight: bold;
            color: #667eea;
        }
        .npc-status {
            padding: 5px 15px;
            border-radius: 20px;
            font-size: 0.9em;
            font-weight: bold;
        }
        .status-active {
            background: #4caf50;
            color: white;
        }
        .status-inactive {
            background: #f44336;
            color: white;
        }
        .npc-info {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 10px;
            margin-bottom: 15px;
        }
        .info-item {
            display: flex;
            flex-direction: column;
        }
        .info-label {
            font-size: 0.85em;
            color: #666;
            margin-bottom: 5px;
        }
        .info-value {
            font-size: 1.1em;
            font-weight: bold;
            color: #333;
        }
        .view-details-btn {
            width: 100%;
            padding: 14px;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: white;
            border: none;
            border-radius: 10px;
            font-size: 1em;
            font-weight: bold;
            cursor: pointer;
            transition: all 0.3s;
            box-shadow: 0 4px 15px rgba(102, 126, 234, 0.4);
            position: relative;
            overflow: hidden;
        }
        .view-details-btn::before {
            content: '';
            position: absolute;
            top: 50%;
            left: 50%;
            width: 0;
            height: 0;
            border-radius: 50%;
            background: rgba(255, 255, 255, 0.3);
            transform: translate(-50%, -50%);
            transition: width 0.6s, height 0.6s;
        }
        .view-details-btn:hover::before {
            width: 300px;
            height: 300px;
        }
        .view-details-btn:hover {
            transform: translateY(-2px);
            box-shadow: 0 6px 20px rgba(102, 126, 234, 0.5);
        }
        .view-details-btn:active {
            transform: translateY(0);
        }
        .modal {
            display: none;
            position: fixed;
            top: 0;
            left: 0;
            width: 100%;
            height: 100%;
            background: rgba(0, 0, 0, 0.7);
            z-index: 1000;
            overflow-y: auto;
        }
        .modal.active {
            display: flex;
            justify-content: center;
            align-items: flex-start;
            padding: 20px;
        }
        .modal-content {
            background: white;
            backdrop-filter: blur(10px);
            border-radius: 20px;
            padding: 30px;
            max-width: 1200px;
            width: 100%;
            margin: 20px auto;
            max-height: 90vh;
            overflow-y: auto;
            box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
            border: 1px solid rgba(255, 255, 255, 0.2);
            animation: modalSlideIn 0.3s ease-out;
        }
        @keyframes modalSlideIn {
            from { opacity: 0; transform: scale(0.9) translateY(-20px); }
            to { opacity: 1; transform: scale(1) translateY(0); }
        }
        .modal-header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            margin-bottom: 30px;
            padding-bottom: 20px;
            border-bottom: 2px solid #f0f0f0;
        }
        .modal-title {
            font-size: 2em;
            color: #667eea;
        }
        .close-btn {
            background: #f44336;
            color: white;
            border: none;
            padding: 10px 20px;
            border-radius: 8px;
            cursor: pointer;
            font-size: 1em;
        }
        .tabs {
            display: flex;
            gap: 10px;
            margin-bottom: 20px;
            border-bottom: 2px solid #f0f0f0;
        }
        .tab {
            padding: 12px 24px;
            background: none;
            border: none;
            cursor: pointer;
            font-size: 1em;
            color: #666;
            border-bottom: 3px solid transparent;
            transition: all 0.3s;
        }
        .tab.active {
            color: #667eea;
            border-bottom-color: #667eea;
            font-weight: bold;
        }
        .tab-content {
            display: none;
        }
        .tab-content.active {
            display: block;
        }
        .sub-tabs {
            display: flex;
            gap: 10px;
            margin-bottom: 20px;
            border-bottom: 2px solid #f0f0f0;
        }
        .sub-tab {
            padding: 10px 20px;
            background: none;
            border: none;
            cursor: pointer;
            font-size: 0.95em;
            color: #666;
            border-bottom: 3px solid transparent;
            transition: all 0.3s;
        }
        .sub-tab.active {
            color: #667eea;
            border-bottom-color: #667eea;
            font-weight: bold;
        }
        .sub-tab:hover {
            color: #667eea;
        }
        .sub-tab-content {
            display: none;
        }
        .sub-tab-content.active {
            display: block;
        }
        .action-code {
            background-color: #2d2d2d;
            padding: 6px 12px;
            border-radius: 6px;
            font-family: 'Courier New', monospace;
            color: #81C784;
            font-size: 0.95em;
            display: inline-block;
            border-left: 3px solid #4CAF50;
            word-break: break-all;
        }
        .data-section {
            margin-bottom: 30px;
        }
        .data-section h3 {
            color: #667eea;
            margin-bottom: 15px;
            font-size: 1.3em;
        }
        .data-grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
            gap: 15px;
        }
        .data-card {
            background: linear-gradient(135deg, #f8f9fa 0%, #ffffff 100%);
            padding: 20px;
            border-radius: 12px;
            border-left: 4px solid #667eea;
            box-shadow: 0 2px 8px rgba(0, 0, 0, 0.05);
            transition: all 0.3s;
        }
        .data-card:hover {
            transform: translateX(5px);
            box-shadow: 0 4px 12px rgba(102, 126, 234, 0.2);
        }
        .data-card-label {
            font-size: 0.9em;
            color: #666;
            margin-bottom: 5px;
        }
        .data-card-value {
            font-size: 1.2em;
            font-weight: bold;
            color: #333;
        }
        .json-viewer {
            background: #1e1e1e;
            color: #d4d4d4;
            padding: 20px;
            border-radius: 8px;
            font-family: 'Courier New', monospace;
            font-size: 0.9em;
            overflow-x: auto;
            max-height: 500px;
            overflow-y: auto;
        }
        table {
            width: 100%;
            border-collapse: collapse;
            background: white;
            border-radius: 8px;
            overflow: hidden;
            box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
        }
        th {
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: white;
            padding: 15px;
            text-align: left;
            font-weight: 600;
            font-size: 0.95em;
        }
        td {
            padding: 12px 15px;
            border-bottom: 1px solid #f0f0f0;
        }
        tr:hover {
            background: #f8f9fa;
        }
        tr:last-child td {
            border-bottom: none;
        }
        .icon {
            display: inline-block;
            width: 20px;
            height: 20px;
            margin-right: 8px;
            vertical-align: middle;
            font-size: 1.2em;
        }
        .progress-bar {
            width: 100%;
            height: 24px;
            background: #e0e0e0;
            border-radius: 12px;
            overflow: hidden;
            position: relative;
        }
        .progress-fill {
            height: 100%;
            background: linear-gradient(90deg, #4caf50 0%, #8bc34a 100%);
            transition: width 0.3s;
            display: flex;
            align-items: center;
            justify-content: center;
            color: white;
            font-size: 0.85em;
            font-weight: bold;
        }
        .progress-fill.health {
            background: linear-gradient(90deg, #f44336 0%, #ff9800 50%, #4caf50 100%);
        }
        .progress-fill.food {
            background: linear-gradient(90deg, #ff9800 0%, #ffc107 50%, #4caf50 100%);
        }
        .badge {
            display: inline-block;
            padding: 4px 12px;
            border-radius: 12px;
            font-size: 0.85em;
            font-weight: 600;
        }
        .badge-success {
            background: #4caf50;
            color: white;
        }
        .badge-info {
            background: #2196f3;
            color: white;
        }
        .badge-warning {
            background: #ff9800;
            color: white;
        }
        .badge-danger {
            background: #f44336;
            color: white;
        }
        .badge-error {
            background: #f44336;
            color: white;
        }
        .message-bubble {
            margin-bottom: 15px;
            padding: 12px 16px;
            border-radius: 12px;
            max-width: 80%;
            word-wrap: break-word;
        }
        .message-user {
            background: #e3f2fd;
            margin-left: auto;
            text-align: right;
        }
        .message-assistant {
            background: #f5f5f5;
        }
        .message-system {
            background: #fff3e0;
            font-style: italic;
        }
        .coords {
            font-family: 'Courier New', monospace;
            background: #f5f5f5;
            padding: 4px 8px;
            border-radius: 4px;
        }
        .message-content {
            max-width: 500px;
            word-wrap: break-word;
            white-space: pre-wrap;
        }
        .message-collapsible {
            position: relative;
        }
        .message-preview {
            max-height: 150px;
            overflow: hidden;
            position: relative;
        }
        .message-preview::after {
            content: '';
            position: absolute;
            bottom: 0;
            left: 0;
            right: 0;
            height: 40px;
            background: linear-gradient(to bottom, transparent, rgba(255, 255, 255, 0.95));
            pointer-events: none;
        }
        .message-full {
            max-height: none;
        }
        .message-toggle {
            background: #667eea;
            color: white;
            border: none;
            padding: 4px 12px;
            border-radius: 4px;
            cursor: pointer;
            font-size: 0.85em;
            margin-top: 8px;
            transition: all 0.3s;
        }
        .message-toggle:hover {
            background: #5568d3;
            transform: translateY(-1px);
        }
        .message-toggle:active {
            transform: translateY(0);
        }
        
        .stat-card {
            background: linear-gradient(135deg, #f8f9fa 0%, #ffffff 100%);
            padding: 20px;
            border-radius: 12px;
            border-left: 4px solid #667eea;
            text-align: center;
        }
        .stat-value {
            font-size: 2em;
            font-weight: bold;
            color: #667eea;
            margin: 10px 0;
        }
        .stat-label {
            color: #666;
            font-size: 0.9em;
        }
        .loading {
            text-align: center;
            padding: 40px;
            color: #666;
        }
        .close-btn {
            background: #f44336;
            color: white;
            border: none;
            padding: 10px 20px;
            border-radius: 8px;
            cursor: pointer;
            font-size: 1em;
            margin-left: 10px;
        }
    </style>
</head>
<body>
    <div class="container">
        <header>
            <h1>🎮 CraftAgent NPC Dashboard</h1>
            <p class="subtitle">Monitor and manage your NPCs in real-time</p>
        </header>
        
        <div class="npcs-grid" id="npcsGrid">
            <div class="loading">Loading NPCs...</div>
        </div>
    </div>
    
    <div class="modal" id="npcModal">
        <div class="modal-content">
            <div class="modal-header">
                <h2 class="modal-title" id="modalTitle">NPC Details</h2>
                <div>
                    <button class="close-btn" onclick="closeModal()">✕ Close</button>
                </div>
            </div>
            
            <div class="tabs">
                <button class="tab active" onclick="switchTab('overview')">Overview</button>
                <button class="tab" onclick="switchTab('state')">State</button>
                <button class="tab" onclick="switchTab('context')">Context</button>
                <button class="tab" onclick="switchTab('messages')">Messages</button>
                <button class="tab" onclick="switchTab('mail')">Mail</button>
                <button class="tab" onclick="switchTab('memory')">Memory</button>
            </div>
            
            <div id="overview" class="tab-content active">
                <div class="data-section">
                    <h3>Basic Information</h3>
                    <div class="data-grid" id="overviewGrid"></div>
                </div>
            </div>
            
            <div id="state" class="tab-content">
                <div class="loading">Loading state...</div>
            </div>
            
            <div id="context" class="tab-content">
                <div class="loading">Loading context...</div>
            </div>
            
            <div id="messages" class="tab-content">
                <div class="sub-tabs">
                    <button class="sub-tab active" onclick="switchSubTab('messages-content', 'actions-content', event)">💬 Messages</button>
                    <button class="sub-tab" onclick="switchSubTab('actions-content', 'messages-content', event)">⚡ Actions</button>
                </div>
                <div id="messages-content" class="sub-tab-content active">
                    <div class="loading">Loading messages...</div>
                </div>
                <div id="actions-content" class="sub-tab-content">
                    <div class="loading">Loading actions...</div>
                </div>
            </div>
            
            <div id="mail" class="tab-content">
                <div class="loading">Loading mail...</div>
            </div>
            
            <div id="memory" class="tab-content">
                <div class="loading">Loading memory...</div>
            </div>
        </div>
    </div>
    
    <script>
        let currentNPCUuid = null;
        let refreshInterval = null;
        let refreshIntervalMs = 5000; // Default 5 seconds
        
        // Get config and set up auto-refresh
        async function initAutoRefresh() {
            try {
                const response = await fetch('/api/config');
                const config = await response.json();
                // Use max of both intervals, convert to milliseconds
                refreshIntervalMs = Math.max(config.llmProcessingInterval || 5, config.llmMinInterval || 2) * 1000;
                console.log('Auto-refresh interval set to', refreshIntervalMs / 1000, 'seconds');
                
                // Start auto-refresh
                startAutoRefresh();
            } catch (error) {
                console.error('Error loading config, using default refresh interval:', error);
                startAutoRefresh();
            }
        }
        
        // Start auto-refresh timer
        function startAutoRefresh() {
            if (refreshInterval) {
                clearInterval(refreshInterval);
            }
            
            refreshInterval = setInterval(() => {
                // Always reload NPC list
                loadNPCs();
                
                // If viewing a specific NPC, reload all its data
                if (currentNPCUuid) {
                    loadNPCOverview(currentNPCUuid);
                    loadNPCState(currentNPCUuid);
                    loadNPCContext(currentNPCUuid);
                    loadNPCMessages(currentNPCUuid);
                    loadNPCMail(currentNPCUuid);
                    loadNPCMemory(currentNPCUuid);
                }
            }, refreshIntervalMs);
        }
        
        // Initialize when page loads
        window.addEventListener('load', function() {
            loadNPCs();
            initAutoRefresh();
        });
        
        // Cleanup on page unload
        window.addEventListener('beforeunload', function() {
            if (refreshInterval) {
                clearInterval(refreshInterval);
            }
        });
        
        async function loadNPCs() {
            try {
                const response = await fetch('/api/npcs');
                const npcs = await response.json();
                displayNPCs(npcs);
            } catch (error) {
                document.getElementById('npcsGrid').innerHTML = 
                    '<div class="loading">Error loading NPCs: ' + error.message + '</div>';
            }
        }
        
        function displayNPCs(npcs) {
            const grid = document.getElementById('npcsGrid');
            if (npcs.length === 0) {
                grid.innerHTML = '<div class="loading">No NPCs found</div>';
                return;
            }
            
            grid.innerHTML = npcs.map(npc => `
                <div class="npc-card" onclick="viewNPC('${npc.uuid}')">
                    <div class="npc-header">
                        <div class="npc-name">${escapeHtml(npc.name)}</div>
                        <div class="npc-status ${npc.isActive ? 'status-active' : 'status-inactive'}">
                            ${npc.isActive ? 'Active' : 'Inactive'}
                        </div>
                    </div>
                    <div class="npc-info">
                        <div class="info-item">
                            <div class="info-label">Age</div>
                            <div class="info-value">${npc.age} years</div>
                        </div>
                        <div class="info-item">
                            <div class="info-label">Gender</div>
                            <div class="info-value">${escapeHtml(npc.gender)}</div>
                        </div>
                        <div class="info-item">
                            <div class="info-label">Health</div>
                            <div class="info-value">${npc.health.toFixed(1)} / 20</div>
                        </div>
                        <div class="info-item">
                            <div class="info-label">Food</div>
                            <div class="info-value">${npc.food} / 20</div>
                        </div>
                    </div>
                    <button class="view-details-btn" onclick="event.stopPropagation(); viewNPC('${npc.uuid}')">
                        View Details
                    </button>
                </div>
            `).join('');
        }
        
        async function viewNPC(uuid) {
            currentNPCUuid = uuid;
            const modal = document.getElementById('npcModal');
            modal.classList.add('active');
            
            // Load all data
            await Promise.all([
                loadNPCOverview(uuid),
                loadNPCState(uuid),
                loadNPCContext(uuid),
                loadNPCMessages(uuid),
                loadNPCMail(uuid),
                loadNPCMemory(uuid),
            ]);
        }
        
        async function loadNPCOverview(uuid) {
            try {
                const [npcResponse, contextResponse] = await Promise.all([
                    fetch(`/api/npc/${uuid}`),
                    fetch(`/api/npc/${uuid}/context`).catch(() => null)
                ]);
                const npc = await npcResponse.json();
                const context = contextResponse ? await contextResponse.json() : null;
                
                document.getElementById('modalTitle').textContent = npc.name + ' - Details';
                
                let actionStateHtml = '';
                let navigationStateHtml = '';
                
                if (context) {
                    // Add action state card
                    if (context.actionState) {
                        const actionType = context.actionState.actionType || 'idle';
                        const actionBadge = actionType === 'idle' ? 'badge-warning' : 'badge-success';
                        actionStateHtml = `
                            <div class="data-card">
                                <div class="data-card-label">⚡ Current Action</div>
                                <div class="data-card-value">
                                    <span class="badge ${actionBadge}">${escapeHtml(actionType.toUpperCase())}</span>
                                    <div style="margin-top: 5px; font-size: 0.9em; color: #666;">
                                        ${escapeHtml(context.actionState.actionDescription || 'N/A')}
                                    </div>
                                    <div style="margin-top: 5px; font-size: 0.85em; color: #999;">
                                        Duration: ${(context.actionState.timeInCurrentAction / 1000).toFixed(1)}s
                                    </div>
                                </div>
                            </div>
                        `;
                    }
                    
                    // Add navigation state card
                    if (context.navigation) {
                        navigationStateHtml = `
                            <div class="data-card">
                                <div class="data-card-label">🧭 Navigation</div>
                                <div class="data-card-value">
                                    <span class="badge badge-info">${escapeHtml(context.navigation.state || 'idle')}</span>
                                    <div style="margin-top: 5px; font-size: 0.9em; color: #666;">
                                        ${escapeHtml(context.navigation.stateDescription || 'N/A')}
                                    </div>
                                    ${context.navigation.destination ? `
                                        <div style="margin-top: 5px; font-size: 0.85em; color: #999;">
                                            Destination: <span class="coords">${context.navigation.destination.x}, ${context.navigation.destination.y}, ${context.navigation.destination.z}</span>
                                        </div>
                                    ` : ''}
                                </div>
                            </div>
                        `;
                    }
                }
                
                document.getElementById('overviewGrid').innerHTML = `
                    <div class="data-card">
                        <div class="data-card-label">Name</div>
                        <div class="data-card-value">${escapeHtml(npc.name)}</div>
                    </div>
                    <div class="data-card">
                        <div class="data-card-label">Status</div>
                        <div class="data-card-value">
                            <span class="badge ${npc.isActive ? 'badge-success' : 'badge-danger'}">
                                ${npc.isActive ? 'Active' : 'Inactive'}
                            </span>
                        </div>
                    </div>
                    <div class="data-card">
                        <div class="data-card-label">Config UUID</div>
                        <div class="data-card-value"><span class="coords">${escapeHtml(npc.uuid)}</span></div>
                    </div>
                    ${npc.entityUuid ? `
                    <div class="data-card">
                        <div class="data-card-label">Entity UUID</div>
                        <div class="data-card-value"><span class="coords">${escapeHtml(npc.entityUuid)}</span></div>
                    </div>
                    ` : ''}
                    <div class="data-card">
                        <div class="data-card-label">Age</div>
                        <div class="data-card-value">${npc.age} years</div>
                    </div>
                    <div class="data-card">
                        <div class="data-card-label">Gender</div>
                        <div class="data-card-value">${escapeHtml(npc.gender)}</div>
                    </div>
                    <div class="data-card">
                        <div class="data-card-label">Position</div>
                        <div class="data-card-value"><span class="coords">${npc.position.x.toFixed(1)}, ${npc.position.y.toFixed(1)}, ${npc.position.z.toFixed(1)}</span></div>
                    </div>
                    <div class="data-card">
                        <div class="data-card-label">Health</div>
                        <div class="data-card-value">${npc.health.toFixed(1)} / 20</div>
                    </div>
                    <div class="data-card">
                        <div class="data-card-label">Food</div>
                        <div class="data-card-value">${npc.food} / 20</div>
                    </div>
                    ${actionStateHtml}
                    ${navigationStateHtml}
                    <div class="data-card">
                        <div class="data-card-label">LLM Type</div>
                        <div class="data-card-value">${escapeHtml(npc.llmType)}</div>
                    </div>
                    <div class="data-card">
                        <div class="data-card-label">LLM Model</div>
                        <div class="data-card-value">${escapeHtml(npc.llmModel || 'N/A')}</div>
                    </div>
                    ${npc.ollamaUrl ? `
                    <div class="data-card">
                        <div class="data-card-label">Ollama URL</div>
                        <div class="data-card-value"><span class="coords">${escapeHtml(npc.ollamaUrl)}</span></div>
                    </div>
                    ` : ''}
                    ${npc.lmStudioUrl ? `
                    <div class="data-card">
                        <div class="data-card-label">LM Studio URL</div>
                        <div class="data-card-value"><span class="coords">${escapeHtml(npc.lmStudioUrl)}</span></div>
                    </div>
                    ` : ''}
                    ${npc.openRouterModel ? `
                    <div class="data-card">
                        <div class="data-card-label">OpenRouter Model</div>
                        <div class="data-card-value"><span class="coords">${escapeHtml(npc.openRouterModel)}</span></div>
                    </div>
                    ` : ''}
                `;
            } catch (error) {
                document.getElementById('overviewGrid').innerHTML = 
                    '<div class="loading">Error: ' + error.message + '</div>';
            }
        }
        
        async function loadNPCState(uuid) {
            try {
                const response = await fetch(`/api/npc/${uuid}/state`);
                const state = await response.json();
                const healthPercent = (state.health / 20) * 100;
                const foodPercent = (state.food / 20) * 100;
                document.getElementById('state').innerHTML = `
                    <div class="data-section">
                        <h3>📊 Current Statistics</h3>
                        <div class="data-grid" style="grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));">
                            <div class="stat-card">
                                <div class="stat-label">❤️ Health</div>
                                <div class="stat-value">${state.health.toFixed(1)}</div>
                                <div class="progress-bar">
                                    <div class="progress-fill health" style="width: ${healthPercent}%">${healthPercent.toFixed(0)}%</div>
                                </div>
                            </div>
                            <div class="stat-card">
                                <div class="stat-label">🍖 Food</div>
                                <div class="stat-value">${state.food}</div>
                                <div class="progress-bar">
                                    <div class="progress-fill food" style="width: ${foodPercent}%">${foodPercent.toFixed(0)}%</div>
                                </div>
                            </div>
                            <div class="stat-card">
                                <div class="stat-label">🌍 Biome</div>
                                <div class="stat-value" style="font-size: 1.2em;">${escapeHtml(state.biome.replace(/_/g, ' '))}</div>
                            </div>
                        </div>
                    </div>
                    <div class="data-section">
                        <h3>📍 Position</h3>
                        <table>
                            <thead>
                                <tr>
                                    <th><span class="icon">📍</span> Coordinate</th>
                                    <th>Value</th>
                                </tr>
                            </thead>
                            <tbody>
                                <tr>
                                    <td><strong>X</strong></td>
                                    <td><span class="coords">${state.position.x.toFixed(2)}</span></td>
                                </tr>
                                <tr>
                                    <td><strong>Y</strong></td>
                                    <td><span class="coords">${state.position.y.toFixed(2)}</span></td>
                                </tr>
                                <tr>
                                    <td><strong>Z</strong></td>
                                    <td><span class="coords">${state.position.z.toFixed(2)}</span></td>
                                </tr>
                            </tbody>
                        </table>
                    </div>
                `;
            } catch (error) {
                document.getElementById('state').innerHTML = 
                    '<div class="loading">Error: ' + error.message + '</div>';
            }
        }
        
        async function loadNPCContext(uuid) {
            try {
                const response = await fetch(`/api/npc/${uuid}/context`);
                const context = await response.json();
                
                let html = '<div class="data-section"><h3>📊 State Information</h3>';
                html += '<table><thead><tr><th>Property</th><th>Value</th></tr></thead><tbody>';
                html += `<tr><td><span class="icon">📍</span> Position</td><td><span class="coords">${context.state.position.x.toFixed(1)}, ${context.state.position.y.toFixed(1)}, ${context.state.position.z.toFixed(1)}</span></td></tr>`;
                html += `<tr><td><span class="icon">❤️</span> Health</td><td>${context.state.health.toFixed(1)} / 20</td></tr>`;
                html += `<tr><td><span class="icon">🍖</span> Food</td><td>${context.state.food} / 20</td></tr>`;
                html += `<tr><td><span class="icon">🌍</span> Biome</td><td>${escapeHtml(context.state.biome.replace(/_/g, ' '))}</td></tr>`;
                html += '</tbody></table></div>';
                
                html += '<div class="data-section"><h3>🎒 Inventory</h3>';
                html += '<table><thead><tr><th>Slot Type</th><th>Count</th></tr></thead><tbody>';
                html += `<tr><td><span class="icon">📦</span> Hotbar</td><td>${context.inventory.hotbar}</td></tr>`;
                html += `<tr><td><span class="icon">📦</span> Main Inventory</td><td>${context.inventory.mainInventory}</td></tr>`;
                html += `<tr><td><span class="icon">🛡️</span> Armor</td><td>${context.inventory.armor}</td></tr>`;
                html += `<tr><td><span class="icon">✋</span> Off Hand</td><td>${context.inventory.offHand}</td></tr>`;
                html += '</tbody></table></div>';
                
                if (context.nearbyBlocks && Array.isArray(context.nearbyBlocks) && context.nearbyBlocks.length > 0) {
                    html += '<div class="data-section"><h3>🧱 Nearby Blocks</h3>';
                    html += '<table><thead><tr><th>Block Type</th><th>Position</th><th>Mine Level</th><th>Tool Needed</th></tr></thead><tbody>';
                    context.nearbyBlocks.forEach(block => {
                        const pos = block.position || {};
                        const positionStr = pos.x !== undefined ? `${pos.x.toFixed(0)}, ${pos.y.toFixed(0)}, ${pos.z.toFixed(0)}` : 'N/A';
                        const mineLevel = block.mineLevel || 'N/A';
                        const toolNeeded = block.toolNeeded || 'None';
                        html += `<tr>
                            <td><strong>${escapeHtml(block.type || 'Unknown')}</strong></td>
                            <td><span class="coords">${positionStr}</span></td>
                            <td>${escapeHtml(String(mineLevel))}</td>
                            <td>${escapeHtml(toolNeeded)}</td>
                        </tr>`;
                    });
                    html += '</tbody></table></div>';
                } else {
                    html += '<div class="data-section"><h3>🧱 Nearby Blocks</h3>';
                    html += '<p>No blocks detected nearby</p></div>';
                }
                
                if (context.nearbyEntities && Array.isArray(context.nearbyEntities) && context.nearbyEntities.length > 0) {
                    html += '<div class="data-section"><h3>👥 Nearby Entities</h3>';
                    html += '<table><thead><tr><th>Name</th><th>Type</th><th>ID</th><th>Is Player</th></tr></thead><tbody>';
                    context.nearbyEntities.forEach(entity => {
                        const typeBadge = entity.isPlayer ? 'badge-info' : 'badge-warning';
                        const typeText = entity.isPlayer ? 'Player' : 'Entity';
                        html += `<tr>
                            <td><strong>${escapeHtml(entity.name || 'Unknown')}</strong></td>
                            <td><span class="badge ${typeBadge}">${typeText}</span></td>
                            <td><span class="coords">${escapeHtml(String(entity.id || 'N/A'))}</span></td>
                            <td>${entity.isPlayer ? '✅ Yes' : '❌ No'}</td>
                        </tr>`;
                    });
                    html += '</tbody></table></div>';
                } else {
                    html += '<div class="data-section"><h3>👥 Nearby Entities</h3>';
                    html += '<p>No entities detected nearby</p></div>';
                }
                
                // Add navigation state
                if (context.navigation) {
                    html += '<div class="data-section"><h3>🧭 Navigation State</h3>';
                    html += '<table><thead><tr><th>Property</th><th>Value</th></tr></thead><tbody>';
                    html += `<tr><td><span class="icon">📍</span> State</td><td><span class="badge badge-info">${escapeHtml(context.navigation.state || 'idle')}</span></td></tr>`;
                    html += `<tr><td><span class="icon">📝</span> Description</td><td>${escapeHtml(context.navigation.stateDescription || 'N/A')}</td></tr>`;
                    html += `<tr><td><span class="icon">⏱️</span> Time in State</td><td>${(context.navigation.timeInCurrentState / 1000).toFixed(1)}s</td></tr>`;
                    if (context.navigation.destination) {
                        const dest = context.navigation.destination;
                        html += `<tr><td><span class="icon">🎯</span> Destination</td><td><span class="coords">${dest.x}, ${dest.y}, ${dest.z}</span></td></tr>`;
                    }
                    html += '</tbody></table></div>';
                }
                
                // Add action state
                if (context.actionState) {
                    html += '<div class="data-section"><h3>⚡ Current Action</h3>';
                    html += '<table><thead><tr><th>Property</th><th>Value</th></tr></thead><tbody>';
                    const actionType = context.actionState.actionType || 'idle';
                    const actionBadge = actionType === 'idle' ? 'badge-warning' : 'badge-success';
                    html += `<tr><td><span class="icon">🎬</span> Action Type</td><td><span class="badge ${actionBadge}">${escapeHtml(actionType.toUpperCase())}</span></td></tr>`;
                    html += `<tr><td><span class="icon">📝</span> Description</td><td>${escapeHtml(context.actionState.actionDescription || 'N/A')}</td></tr>`;
                    html += `<tr><td><span class="icon">⏱️</span> Duration</td><td>${(context.actionState.timeInCurrentAction / 1000).toFixed(1)}s</td></tr>`;
                    if (context.actionState.actionData && Object.keys(context.actionState.actionData).length > 0) {
                        html += '<tr><td colspan="2"><strong>Action Details:</strong><br>';
                        const actionData = context.actionState.actionData;
                        for (const [key, value] of Object.entries(actionData)) {
                            if (value && typeof value === 'object' && value.x !== undefined) {
                                // Position object
                                html += `<span class="coords" style="margin-right: 10px;">${key}: (${value.x}, ${value.y}, ${value.z})</span>`;
                            } else {
                                html += `<span style="margin-right: 10px;"><strong>${escapeHtml(String(key))}:</strong> ${escapeHtml(String(value))}</span>`;
                            }
                        }
                        html += '</td></tr>';
                    }
                    html += '</tbody></table></div>';
                }
                
                // Add line of sight data
                if (context.lineOfSight) {
                    html += '<div class="data-section"><h3>👁️ Line of Sight</h3>';
                    
                    // Items in line of sight
                    if (context.lineOfSight.items && context.lineOfSight.items.length > 0) {
                        html += '<h4 style="margin-top: 15px; color: #667eea;">📦 Items in Line of Sight</h4>';
                        html += '<table><thead><tr><th>Item Type</th><th>Count</th><th>Position</th><th>Distance</th></tr></thead><tbody>';
                        context.lineOfSight.items.forEach(item => {
                            const pos = item.position || {};
                            const positionStr = pos.x !== undefined ? `${pos.x}, ${pos.y}, ${pos.z}` : 'N/A';
                            html += `<tr>
                                <td><strong>${escapeHtml(item.type || 'Unknown')}</strong></td>
                                <td>${item.count || 0}</td>
                                <td><span class="coords">${positionStr}</span></td>
                                <td>${item.distance ? item.distance.toFixed(2) : 'N/A'}</td>
                            </tr>`;
                        });
                        html += '</tbody></table>';
                    }
                    
                    // Entities in line of sight
                    if (context.lineOfSight.entities && context.lineOfSight.entities.length > 0) {
                        html += '<h4 style="margin-top: 15px; color: #667eea;">👥 Entities in Line of Sight</h4>';
                        html += '<table><thead><tr><th>Name</th><th>Type</th><th>ID</th><th>Is Player</th></tr></thead><tbody>';
                        context.lineOfSight.entities.forEach(entity => {
                            const typeBadge = entity.isPlayer ? 'badge-info' : 'badge-warning';
                            const typeText = entity.isPlayer ? 'Player' : 'Entity';
                            html += `<tr>
                                <td><strong>${escapeHtml(entity.name || 'Unknown')}</strong></td>
                                <td><span class="badge ${typeBadge}">${typeText}</span></td>
                                <td><span class="coords">${escapeHtml(String(entity.id || 'N/A'))}</span></td>
                                <td>${entity.isPlayer ? '✅ Yes' : '❌ No'}</td>
                            </tr>`;
                        });
                        html += '</tbody></table>';
                    }
                    
                    // Target block (where NPC is looking)
                    if (context.lineOfSight.targetBlock) {
                        html += '<h4 style="margin-top: 15px; color: #667eea;">🎯 Target Block (Looking At)</h4>';
                        html += '<table><thead><tr><th>Block Type</th><th>Position</th><th>Mine Level</th><th>Tool Needed</th></tr></thead><tbody>';
                        const targetBlock = context.lineOfSight.targetBlock;
                        const pos = targetBlock.position || {};
                        const positionStr = pos.x !== undefined ? `${pos.x}, ${pos.y}, ${pos.z}` : 'N/A';
                        html += `<tr>
                            <td><strong>${escapeHtml(targetBlock.type || 'Unknown')}</strong></td>
                            <td><span class="coords">${positionStr}</span></td>
                            <td>${escapeHtml(String(targetBlock.mineLevel || 'N/A'))}</td>
                            <td>${escapeHtml(targetBlock.toolNeeded || 'None')}</td>
                        </tr>`;
                        html += '</tbody></table>';
                    }
                    
                    // Visible blocks
                    if (context.lineOfSight.visibleBlocks && context.lineOfSight.visibleBlocks.length > 0) {
                        html += '<h4 style="margin-top: 15px; color: #667eea;">🧱 Visible Blocks</h4>';
                        html += '<table><thead><tr><th>Block Type</th><th>Position</th><th>Mine Level</th><th>Tool Needed</th></tr></thead><tbody>';
                        context.lineOfSight.visibleBlocks.forEach(block => {
                            const pos = block.position || {};
                            const positionStr = pos.x !== undefined ? `${pos.x}, ${pos.y}, ${pos.z}` : 'N/A';
                            html += `<tr>
                                <td><strong>${escapeHtml(block.type || 'Unknown')}</strong></td>
                                <td><span class="coords">${positionStr}</span></td>
                                <td>${escapeHtml(String(block.mineLevel || 'N/A'))}</td>
                                <td>${escapeHtml(block.toolNeeded || 'None')}</td>
                            </tr>`;
                        });
                        html += '</tbody></table>';
                    }
                    
                    html += '</div>';
                }
                
                document.getElementById('context').innerHTML = html;
            } catch (error) {
                document.getElementById('context').innerHTML = 
                    '<div class="loading">Error: ' + error.message + '</div>';
            }
        }
        
        async function loadNPCMessages(uuid) {
            try {
                const response = await fetch(`/api/npc/${uuid}/messages`);
                const messages = await response.json();
                
                // Separate messages and actions
                let messagesHtml = '';
                let actionsHtml = '';
                const allActions = [];
                
                if (messages.length === 0) {
                    document.getElementById('messages-content').innerHTML = 
                        '<div class="loading">No messages in conversation history</div>';
                    document.getElementById('actions-content').innerHTML = 
                        '<div class="loading">No actions found</div>';
                    return;
                }
                
                // Build messages table
                messagesHtml = '<div class="data-section"><h3>💬 Conversation History</h3>';
                messagesHtml += '<table><thead><tr><th>Role</th><th>Message</th><th>Timestamp</th></tr></thead><tbody>';
                
                messages.forEach((msg, index) => {
                    const role = msg.role || 'unknown';
                    const content = msg.content || msg.message || '';
                    const actions = msg.actions || [];
                    const timestamp = msg.timestamp ? new Date(msg.timestamp).toLocaleString() : 'N/A';
                    
                    // Collect actions for the actions tab
                    if (actions && actions.length > 0) {
                        actions.forEach((action, actionIdx) => {
                            allActions.push({
                                action: action,
                                timestamp: timestamp,
                                role: role,
                                messageIndex: index,
                                actionIndex: actionIdx
                            });
                        });
                    }
                    
                    // Get role icon and badge
                    let roleIcon, roleBadge;
                    switch (role) {
                        case 'user':
                            roleIcon = '👤';
                            roleBadge = 'badge-info';
                            break;
                        case 'assistant':
                            roleIcon = '🤖';
                            roleBadge = 'badge-success';
                            break;
                        case 'system':
                            roleIcon = '⚙️';
                            roleBadge = 'badge-warning';
                            break;
                        default:
                            roleIcon = '❓';
                            roleBadge = 'badge-warning';
                    }
                    
                    // Format messages - only show message content, not actions
                    let formattedContent;
                    switch (role) {
                        case 'assistant':
                            // Only show message text, not actions
                            formattedContent = content ? escapeHtml(content) : '<em>No message text</em>';
                            break;
                        case 'system':
                            formattedContent = formatSystemMessage(content);
                            break;
                        default:
                            formattedContent = escapeHtml(content);
                    }
                    
                    // Determine if message is long (more than 500 characters)
                    const isLong = content.length > 500;
                    const messageId = `msg-${index}`;
                    
                    if (isLong) {
                        messagesHtml += `<tr>
                            <td><span class="badge ${roleBadge}">${roleIcon} ${role.toUpperCase()}</span></td>
                            <td>
                                <div class="message-collapsible">
                                    <div id="${messageId}-preview" class="message-preview message-content">${formattedContent}</div>
                                    <div id="${messageId}-full" class="message-full message-content" style="display: none;">${formattedContent}</div>
                                    <button class="message-toggle" onclick="toggleMessage('${messageId}')" id="${messageId}-btn">Show more</button>
                                </div>
                            </td>
                            <td>${timestamp}</td>
                        </tr>`;
                    } else {
                        messagesHtml += `<tr>
                            <td><span class="badge ${roleBadge}">${roleIcon} ${role.toUpperCase()}</span></td>
                            <td class="message-content">${formattedContent}</td>
                            <td>${timestamp}</td>
                        </tr>`;
                    }
                });
                
                messagesHtml += '</tbody></table></div>';
                document.getElementById('messages-content').innerHTML = messagesHtml;
                
                // Build actions table
                if (allActions.length === 0) {
                    actionsHtml = '<div class="data-section"><h3>⚡ Actions</h3><p>No actions found in conversation history</p></div>';
                } else {
                    actionsHtml = '<div class="data-section"><h3>⚡ Actions History</h3>';
                    actionsHtml += '<table><thead><tr><th>#</th><th>Action</th><th>Timestamp</th><th>From Message</th></tr></thead><tbody>';
                    
                    allActions.forEach((actionData, idx) => {
                        const actionBadge = actionData.role === 'assistant' ? 'badge-success' : 'badge-info';
                        actionsHtml += `<tr>
                            <td><strong style="color: #667eea;">${idx + 1}</strong></td>
                            <td><span class="action-code">${escapeHtml(actionData.action)}</span></td>
                            <td>${actionData.timestamp}</td>
                            <td><span class="badge ${actionBadge}">Message #${actionData.messageIndex + 1}</span></td>
                        </tr>`;
                    });
                    
                    actionsHtml += '</tbody></table></div>';
                }
                
                document.getElementById('actions-content').innerHTML = actionsHtml;
            } catch (error) {
                document.getElementById('messages-content').innerHTML = 
                    '<div class="loading">Error: ' + error.message + '</div>';
                document.getElementById('actions-content').innerHTML = 
                    '<div class="loading">Error: ' + error.message + '</div>';
            }
        }
        
        async function loadNPCMail(uuid) {
            try {
                const response = await fetch(`/api/npc/${uuid}/mail`);
                const mail = await response.json();
                
                if (!mail || mail.length === 0) {
                    document.getElementById('mail').innerHTML = 
                        '<div class="loading">📭 No mail messages</div>';
                    return;
                }
                
                let html = '<div class="data-section"><h3>📬 Mail Messages</h3>';
                html += '<table><thead><tr><th>From</th><th>Content</th><th>Date</th></tr></thead><tbody>';
                
                mail.forEach(msg => {
                    const sender = msg.senderName || msg.sender || 'Unknown';
                    const content = msg.content || msg.message || '';
                    const date = msg.timestamp ? new Date(msg.timestamp).toLocaleString() : msg.createdAt || 'N/A';
                    
                    html += `<tr>
                        <td><strong>${escapeHtml(sender)}</strong></td>
                        <td style="max-width: 400px; word-wrap: break-word;">${escapeHtml(content)}</td>
                        <td>${date}</td>
                    </tr>`;
                });
                
                html += '</tbody></table></div>';
                document.getElementById('mail').innerHTML = html;
            } catch (error) {
                document.getElementById('mail').innerHTML = 
                    '<div class="loading">Error: ' + error.message + '</div>';
            }
        }
        
        async function loadNPCMemory(uuid) {
            try {
                const response = await fetch(`/api/npc/${uuid}/memory`);
                const memory = await response.json();
                
                let html = '';
                
                if (memory.privateBook && memory.privateBook.length > 0) {
                    html += '<div class="data-section"><h3>📔 Private Book</h3>';
                    html += '<table><thead><tr><th>Page Title</th><th>Content</th><th>Last Updated</th></tr></thead><tbody>';
                    memory.privateBook.forEach(page => {
                        const date = page.timestamp ? new Date(page.timestamp).toLocaleString() : 'N/A';
                        html += `<tr>
                            <td><strong>${escapeHtml(page.pageTitle || 'Untitled')}</strong></td>
                            <td style="max-width: 500px; word-wrap: break-word;">${escapeHtml(page.content || 'Empty page')}</td>
                            <td>${date}</td>
                        </tr>`;
                    });
                    html += '</tbody></table></div>';
                } else {
                    html += '<div class="data-section"><h3>📔 Private Book</h3><p>No pages in private book</p></div>';
                }
                
                if (memory.sharebook && memory.sharebook.length > 0) {
                    html += '<div class="data-section"><h3>📖 Shared Book</h3>';
                    html += '<table><thead><tr><th>Page Title</th><th>Content</th><th>Last Updated</th></tr></thead><tbody>';
                    memory.sharebook.forEach(page => {
                        const date = page.timestamp ? new Date(page.timestamp).toLocaleString() : 'N/A';
                        html += `<tr>
                            <td><strong>${escapeHtml(page.pageTitle || 'Untitled')}</strong></td>
                            <td style="max-width: 500px; word-wrap: break-word;">${escapeHtml(page.content || 'Empty page')}</td>
                            <td>${date}</td>
                        </tr>`;
                    });
                    html += '</tbody></table></div>';
                } else {
                    html += '<div class="data-section"><h3>📖 Shared Book</h3><p>No pages in shared book</p></div>';
                }
                
                document.getElementById('memory').innerHTML = html || '<div class="loading">No memory data available</div>';
            } catch (error) {
                document.getElementById('memory').innerHTML = 
                    '<div class="loading">Error: ' + error.message + '</div>';
            }
        }
        
        function switchTab(tabName) {
            // Hide all tabs
            document.querySelectorAll('.tab-content').forEach(tab => {
                tab.classList.remove('active');
            });
            document.querySelectorAll('.tab').forEach(tab => {
                tab.classList.remove('active');
            });
            
            // Show selected tab
            document.getElementById(tabName).classList.add('active');
            event.target.classList.add('active');
            
        }
        
        function switchSubTab(showId, hideId, event) {
            // Hide all sub-tabs
            document.querySelectorAll('.sub-tab-content').forEach(tab => {
                tab.classList.remove('active');
            });
            document.querySelectorAll('.sub-tab').forEach(tab => {
                tab.classList.remove('active');
            });
            
            // Show selected sub-tab
            document.getElementById(showId).classList.add('active');
            event.target.classList.add('active');
        }
        
        function closeModal() {
            document.getElementById('npcModal').classList.remove('active');
            currentNPCUuid = null;
        }
        
        
        function escapeHtml(text) {
            const div = document.createElement('div');
            div.textContent = text;
            return div.innerHTML;
        }
        
        function toggleMessage(messageId) {
            const preview = document.getElementById(messageId + '-preview');
            const full = document.getElementById(messageId + '-full');
            const btn = document.getElementById(messageId + '-btn');
            
            if (preview && full && btn) {
                if (preview.style.display === 'none') {
                    // Collapse
                    preview.style.display = 'block';
                    full.style.display = 'none';
                    btn.textContent = 'Show more';
                } else {
                    // Expand
                    preview.style.display = 'none';
                    full.style.display = 'block';
                    btn.textContent = 'Show less';
                }
            }
        }
        
        /**
         * Formats message output for assistant messages with actions.
         */
        function formatStructuredOutput(text, actions) {
            if (!text && (!actions || actions.length === 0)) return '';
            
            let html = '';
            
            // Display message if present
            if (text && text.trim() !== '') {
                html += '<div class="message-text" style="margin-bottom: 10px;">';
                html += escapeHtml(text);
                html += '</div>';
            }
            
            // Display actions if present
            if (actions && actions.length > 0) {
                html += '<div class="actions-section" style="margin-top: 10px; padding: 8px; background-color: #2d2d2d; border-radius: 4px; border-left: 3px solid #4CAF50;">';
                html += '<div style="font-weight: bold; color: #4CAF50; margin-bottom: 5px;">⚡ Actions:</div>';
                html += '<ul style="margin: 0; padding-left: 20px; list-style-type: none;">';
                actions.forEach((action, idx) => {
                    html += `<li style="margin: 3px 0; color: #E0E0E0;">
                        <span style="color: #FFC107;">${idx + 1}.</span> 
                        <code style="background-color: #1a1a1a; padding: 2px 6px; border-radius: 3px; font-family: 'Courier New', monospace; color: #81C784;">${escapeHtml(action)}</code>
                    </li>`;
                });
                html += '</ul>';
                html += '</div>';
            }
            
            return html || escapeHtml(text || '');
        }
        
        /**
         * Formats system messages with proper HTML rendering.
         * Converts markdown-like syntax (===, **, -, etc.) to HTML.
         */
        function formatSystemMessage(text) {
            if (!text) return '';
            
            // First escape HTML to prevent XSS
            let formatted = escapeHtml(text);
            
            // Convert section headers (=== HEADER ===)
            formatted = formatted.replace(/=== (.+?) ===/g, '<h4 style="margin-top: 15px; margin-bottom: 8px; color: #4CAF50; border-bottom: 1px solid #4CAF50; padding-bottom: 3px; font-weight: bold;">$1</h4>');
            
            // Convert bold text (**text**) - escape asterisks properly
            formatted = formatted.replace(/\\*\\*(.+?)\\*\\*/g, '<strong style="color: #FFC107;">$1</strong>');
            
            // Convert bullet points (- item) - must be done before line break conversion
            formatted = formatted.replace(/^[\\s]*- (.+)$/gm, '<li style="margin-left: 20px; margin-top: 3px; margin-bottom: 3px;">$1</li>');
            
            // Wrap consecutive list items in <ul>
            formatted = formatted.replace(/(<li[^>]*>.*?<\\/li>(?:<br>)?)+/g, function(match) {
                return '<ul style="margin-top: 8px; margin-bottom: 8px; padding-left: 20px;">' + match.replace(/<br>/g, '') + '</ul>';
            });
            
            // Convert arrows (→) to styled arrows
            formatted = formatted.replace(/→/g, '<span style="color: #2196F3; font-weight: bold;">→</span>');
            
            // Add monospace styling for code-like blocks (JSON examples, etc.)
            formatted = formatted.replace(/(\\{[^}]+\\})/g, '<code style="background-color: #2d2d2d; padding: 2px 6px; border-radius: 3px; font-family: monospace; color: #f8f8f2; font-size: 0.9em;">$1</code>');
            
            // Convert double line breaks to paragraph breaks, single line breaks to <br>
            // Split by double line breaks first
            let parts = formatted.split(/\\n\\n+/);
            formatted = parts.map(part => {
                // Convert single line breaks to <br> within each part
                part = part.replace(/\\n/g, '<br>');
                // Wrap in paragraph if it's not already a block element
                if (!part.trim().startsWith('<h4') && !part.trim().startsWith('<ul') && !part.trim().startsWith('<li')) {
                    return '<p style="margin-top: 5px; margin-bottom: 5px; line-height: 1.5;">' + part + '</p>';
                }
                return part;
            }).join('');
            
            return formatted;
        }
        
        // Load NPCs on page load
        loadNPCs();
        
        // Auto-refresh handles all updates periodically
    </script>
</body>
</html>
""";
    }
}

