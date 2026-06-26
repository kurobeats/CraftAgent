package me.prskid1000.craftagent.client.gui.screen;

import io.wispforest.owo.ui.component.*;
import io.wispforest.owo.ui.container.Containers;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.Sizing;
import me.prskid1000.craftagent.client.networking.ClientNetworkManager;
import me.prskid1000.craftagent.config.NPCConfig;
import me.prskid1000.craftagent.llm.LLMType;
import me.prskid1000.craftagent.networking.packet.CreateNpcPacket;
import me.prskid1000.craftagent.networking.packet.UpdateNpcConfigPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import static me.prskid1000.craftagent.CraftAgent.MOD_ID;

public class NPCConfigScreen extends ConfigScreen<NPCConfig> {

    private static final Identifier ID = Identifier.of(MOD_ID, "npcconfig");

    private static final Sizing INPUT_H = Sizing.fixed(18);
    private static final Sizing PROMPT_H = Sizing.fixed(110);

    public NPCConfigScreen(ClientNetworkManager networkManager, NPCConfig npcConfig, boolean isEdit) {
        super(networkManager, npcConfig, isEdit, ID);
    }

    @Override
    protected void build(FlowLayout rootComponent) {
        // IMPORTANT: operate on the padded inner content container
        FlowLayout content = rootComponent.childById(FlowLayout.class, "content");

        drawNpcNameRow(content);
        drawLLMTypeButtons(content);

        redrawLlmDependentFields(content);

        drawGenderRow(content);
        drawAgeRow(content);
        drawSkipLLMRow(content);

        onPressSaveButton(rootComponent, button -> {
            if (isEdit) networkManager.sendPacket(new UpdateNpcConfigPacket(config));
            else networkManager.sendPacket(new CreateNpcPacket(config));
            close();
        });
    }

    private void drawNpcNameRow(FlowLayout content) {
        LabelComponent npcNameLabel = content.childById(LabelComponent.class, "npcName-label");

        if (isEdit) {
            npcNameLabel.text(Text.of(NPCConfig.EDIT_NPC.formatted(config.getNpcName())));
        } else {
            npcNameLabel.text(Text.of(NPCConfig.NPC_NAME));

            TextAreaComponent npcName = Components.textArea(Sizing.fill(100), INPUT_H)
                    .text(config.getNpcName());
            npcName.onChanged().subscribe(config::setNpcName);

            content.childById(FlowLayout.class, "npcNameRow").child(npcName);
        }
    }

    private void drawLLMTypeButtons(FlowLayout content) {
        content.childById(LabelComponent.class, "llmType-label").text(Text.of(NPCConfig.LLM_TYPE));

        FlowLayout llmTypeRow = content.childById(FlowLayout.class, "llmTypeRow");
        while (llmTypeRow.children().size() > 1) {
            llmTypeRow.removeChild(llmTypeRow.children().get(1));
        }

        FlowLayout buttonRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
        buttonRow.gap(6);

        buttonRow.child(llmTypeButton(content, LLMType.OLLAMA));
        buttonRow.child(llmTypeButton(content, LLMType.LM_STUDIO));
        buttonRow.child(llmTypeButton(content, LLMType.OPEN_ROUTER));

        llmTypeRow.child(buttonRow);
    }

    private ButtonComponent llmTypeButton(FlowLayout content, LLMType type) {
        boolean active = config.getLlmType() == type;

        ButtonComponent btn = Components.button(Text.of(type.toString()), b -> {
            if (isEdit) return;
            config.setLlmType(type);
            redrawLlmDependentFields(content);
            drawLLMTypeButtons(content);
        });

        if (active) btn.active(false);
        if (isEdit) btn.active(false);
        return btn;
    }

    private void redrawLlmDependentFields(FlowLayout content) {
        drawLLMModelRow(content);
        drawLlmInfo(content);
    }

    private void drawLLMModelRow(FlowLayout content) {
        content.childById(LabelComponent.class, "llmModel-label").text(Text.of(NPCConfig.LLM_MODEL));

        FlowLayout llmModelRow = content.childById(FlowLayout.class, "llmModelRow");
        while (llmModelRow.children().size() > 1) {
            llmModelRow.removeChild(llmModelRow.children().get(1));
        }

        TextAreaComponent llmModel = Components.textArea(Sizing.fill(100), INPUT_H)
                .text(config.getLlmModel());
        llmModel.onChanged().subscribe(config::setLlmModel);

        llmModelRow.child(llmModel);
    }

    private void drawLlmInfo(FlowLayout content) {
        FlowLayout llmInfo = content.childById(FlowLayout.class, "llmInfo");
        llmInfo.clearChildren();

        TextAreaComponent urlInput = Components.textArea(Sizing.fill(100), INPUT_H);

        switch (config.getLlmType()) {
            case OLLAMA -> {
                llmInfo.child(Components.label(Text.of(NPCConfig.OLLAMA_URL)).shadow(true));
                urlInput.text(config.getOllamaUrl()).onChanged().subscribe(config::setOllamaUrl);
            }
            case LM_STUDIO -> {
                llmInfo.child(Components.label(Text.of(NPCConfig.LM_STUDIO_URL)).shadow(true));
                urlInput.text(config.getLmStudioUrl()).onChanged().subscribe(config::setLmStudioUrl);
            }
            case OPEN_ROUTER -> {
                // API Key field
                llmInfo.child(Components.label(Text.of(NPCConfig.OPEN_ROUTER_API_KEY)).shadow(true));
                TextAreaComponent apiKeyInput = Components.textArea(Sizing.fill(100), INPUT_H)
                        .text(config.getOpenRouterApiKey());
                apiKeyInput.onChanged().subscribe(config::setOpenRouterApiKey);
                llmInfo.child(apiKeyInput);

                // Model field
                llmInfo.child(Components.label(Text.of(NPCConfig.OPEN_ROUTER_MODEL)).shadow(true)
                        .margins(Insets.top(7)));
                TextAreaComponent modelInput = Components.textArea(Sizing.fill(100), INPUT_H)
                        .text(config.getOpenRouterModel());
                modelInput.onChanged().subscribe(config::setOpenRouterModel);
                llmInfo.child(modelInput);

                // Temperature field
                llmInfo.child(Components.label(Text.of(NPCConfig.OPEN_ROUTER_TEMPERATURE)).shadow(true)
                        .margins(Insets.top(7)));
                TextAreaComponent temperatureInput = Components.textArea(Sizing.fill(100), INPUT_H)
                        .text(String.valueOf(config.getOpenRouterTemperature()));
                temperatureInput.onChanged().subscribe(v -> {
                    if (isEdit) return;
                    try { config.setOpenRouterTemperature(Float.parseFloat(v)); }
                    catch (NumberFormatException ignored) {}
                });
                llmInfo.child(temperatureInput);

                // Max Tokens field
                llmInfo.child(Components.label(Text.of(NPCConfig.OPEN_ROUTER_MAX_TOKENS)).shadow(true)
                        .margins(Insets.top(7)));
                TextAreaComponent maxTokensInput = Components.textArea(Sizing.fill(100), INPUT_H)
                        .text(String.valueOf(config.getOpenRouterMaxTokens()));
                maxTokensInput.onChanged().subscribe(v -> {
                    if (isEdit) return;
                    try { config.setOpenRouterMaxTokens(Integer.parseInt(v)); }
                    catch (NumberFormatException ignored) {}
                });
                llmInfo.child(maxTokensInput);

                // API URL field
                llmInfo.child(Components.label(Text.of(NPCConfig.OPEN_ROUTER_API_URL)).shadow(true)
                        .margins(Insets.top(7)));
                urlInput.text(config.getOpenRouterApiUrl()).onChanged().subscribe(config::setOpenRouterApiUrl);
            }
        }
        llmInfo.child(urlInput);

        llmInfo.child(
                Components.label(Text.of(NPCConfig.CUSTOM_SYSTEM_PROMPT))
                        .shadow(true)
                        .margins(Insets.top(7))
        );

        TextAreaComponent prompt = Components.textArea(Sizing.fill(100), PROMPT_H)
                .text(config.getCustomSystemPrompt());
        prompt.onChanged().subscribe(config::setCustomSystemPrompt);

        llmInfo.child(prompt);
    }

    private void drawGenderRow(FlowLayout content) {
        FlowLayout genderRow = content.childById(FlowLayout.class, "genderRow");
        genderRow.clearChildren();

        genderRow.child(Components.label(Text.of(NPCConfig.GENDER)).shadow(true));

        FlowLayout buttons = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
        buttons.gap(6);

        buttons.child(genderButton(content, "male", "Male"));
        buttons.child(genderButton(content, "female", "Female"));
        buttons.child(genderButton(content, "neutral", "Neutral"));

        genderRow.child(buttons);
    }

    private ButtonComponent genderButton(FlowLayout content, String value, String label) {
        String current = config.getGender();
        if (current == null || current.isEmpty()) current = "neutral";

        boolean active = value.equalsIgnoreCase(current);

        ButtonComponent btn = Components.button(Text.of(label), b -> {
            if (isEdit) return;
            config.setGender(value);
            drawGenderRow(content);
        });

        if (active) btn.active(false);
        if (isEdit) btn.active(false);
        return btn;
    }

    private void drawAgeRow(FlowLayout content) {
        FlowLayout ageRow = content.childById(FlowLayout.class, "ageRow");
        ageRow.clearChildren();

        ageRow.child(Components.label(Text.of(NPCConfig.AGE)).shadow(true));

        TextAreaComponent ageInput = Components.textArea(Sizing.fill(100), INPUT_H)
                .text(String.valueOf(config.getAge()));

        ageInput.onChanged().subscribe(v -> {
            if (isEdit) return;
            try { config.setAge(Integer.parseInt(v)); }
            catch (NumberFormatException ignored) {}
        });

        // Note: TextAreaComponent doesn't have active() method, but changes are already blocked by early return in onChanged
        ageRow.child(ageInput);
    }

    private void drawSkipLLMRow(FlowLayout content) {
        FlowLayout skipLLMRow = content.childById(FlowLayout.class, "skipLLMRow");
        skipLLMRow.clearChildren();

        skipLLMRow.child(Components.label(Text.of(NPCConfig.SKIP_LLM_REQUESTS)).shadow(true));

        CheckboxComponent skipLLMCheckbox = Components.checkbox(Text.of("Skip LLM Requests"));
        skipLLMCheckbox.checked(config.isSkipLLMRequests());
        skipLLMCheckbox.onChanged(config::setSkipLLMRequests);

        skipLLMRow.child(skipLLMCheckbox);
    }
}
