package com.chatcrmlite.backend.services.whatsapp.flows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class FlowSchemaBuilder {

    private final ObjectMapper objectMapper;

    public FlowSchemaBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Builds Meta Flow JSON Version 7.0 from internal CRM fields configuration.
     */
    public String buildMetaFlowJson(String title, String description, String fieldsConfigJson) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("version", "7.0");

            ArrayNode screens = root.putArray("screens");
            ObjectNode screen = screens.addObject();
            screen.put("id", "MAIN_SCREEN");
            screen.put("title", (title != null && !title.isBlank()) ? title : "Form");
            screen.putObject("data");
            screen.put("terminal", true);

            ObjectNode layout = screen.putObject("layout");
            layout.put("type", "SingleColumnLayout");

            ArrayNode layoutChildren = layout.putArray("children");
            ObjectNode form = layoutChildren.addObject();
            form.put("type", "Form");
            form.put("name", "main_form");

            ArrayNode formChildren = form.putArray("children");

            // Header title
            if (title != null && !title.isBlank()) {
                ObjectNode heading = formChildren.addObject();
                heading.put("type", "TextHeading");
                heading.put("text", title);
            }

            // Description body
            if (description != null && !description.isBlank()) {
                ObjectNode desc = formChildren.addObject();
                desc.put("type", "TextBody");
                desc.put("text", description);
            }

            // Parse CRM Field List
            List<String> payloadFieldNames = new ArrayList<>();
            if (fieldsConfigJson != null && !fieldsConfigJson.isBlank()) {
                JsonNode fieldsArray = objectMapper.readTree(fieldsConfigJson);
                if (fieldsArray.isArray()) {
                    for (JsonNode field : fieldsArray) {
                        String fieldName = field.path("name").asText("").trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
                        String fieldLabel = field.path("label").asText(fieldName);
                        String fieldType = field.path("type").asText("TEXT").toUpperCase();
                        boolean required = field.path("required").asBoolean(false);

                        if (fieldName.isBlank()) continue;
                        payloadFieldNames.add(fieldName);

                        switch (fieldType) {
                            case "EMAIL" -> {
                                ObjectNode input = formChildren.addObject();
                                input.put("type", "TextInput");
                                input.put("name", fieldName);
                                input.put("label", fieldLabel);
                                input.put("required", required);
                                input.put("input-type", "email");
                            }
                            case "PHONE" -> {
                                ObjectNode input = formChildren.addObject();
                                input.put("type", "TextInput");
                                input.put("name", fieldName);
                                input.put("label", fieldLabel);
                                input.put("required", required);
                                input.put("input-type", "phone");
                            }
                            case "NUMBER" -> {
                                ObjectNode input = formChildren.addObject();
                                input.put("type", "TextInput");
                                input.put("name", fieldName);
                                input.put("label", fieldLabel);
                                input.put("required", required);
                                input.put("input-type", "number");
                            }
                            case "DATE" -> {
                                ObjectNode datePicker = formChildren.addObject();
                                datePicker.put("type", "DatePicker");
                                datePicker.put("name", fieldName);
                                datePicker.put("label", fieldLabel);
                                datePicker.put("required", required);
                            }
                            case "SELECT", "DROPDOWN" -> {
                                ObjectNode dropdown = formChildren.addObject();
                                dropdown.put("type", "Dropdown");
                                dropdown.put("name", fieldName);
                                dropdown.put("label", fieldLabel);
                                dropdown.put("required", required);
                                ArrayNode dataSource = dropdown.putArray("data-source");
                                JsonNode optionsNode = field.path("options");
                                if (optionsNode.isArray() && optionsNode.size() > 0) {
                                    for (JsonNode opt : optionsNode) {
                                        String optVal = opt.asText();
                                        ObjectNode optObj = dataSource.addObject();
                                        optObj.put("id", optVal.toLowerCase().replaceAll("[^a-z0-9_]", "_"));
                                        optObj.put("title", optVal);
                                    }
                                } else {
                                    ObjectNode defaultOpt = dataSource.addObject();
                                    defaultOpt.put("id", "default_opt");
                                    defaultOpt.put("title", "General");
                                }
                            }
                            case "RADIO" -> {
                                ObjectNode radio = formChildren.addObject();
                                radio.put("type", "RadioButtonsGroup");
                                radio.put("name", fieldName);
                                radio.put("label", fieldLabel);
                                radio.put("required", required);
                                ArrayNode dataSource = radio.putArray("data-source");
                                JsonNode optionsNode = field.path("options");
                                if (optionsNode.isArray() && optionsNode.size() > 0) {
                                    for (JsonNode opt : optionsNode) {
                                        String optVal = opt.asText();
                                        ObjectNode optObj = dataSource.addObject();
                                        optObj.put("id", optVal.toLowerCase().replaceAll("[^a-z0-9_]", "_"));
                                        optObj.put("title", optVal);
                                    }
                                } else {
                                    ObjectNode defaultOpt = dataSource.addObject();
                                    defaultOpt.put("id", "opt_1");
                                    defaultOpt.put("title", "Option 1");
                                }
                            }
                            case "CHECKBOX" -> {
                                ObjectNode checkbox = formChildren.addObject();
                                checkbox.put("type", "CheckboxGroup");
                                checkbox.put("name", fieldName);
                                checkbox.put("label", fieldLabel);
                                checkbox.put("required", required);
                                ArrayNode dataSource = checkbox.putArray("data-source");
                                JsonNode optionsNode = field.path("options");
                                if (optionsNode.isArray() && optionsNode.size() > 0) {
                                    for (JsonNode opt : optionsNode) {
                                        String optVal = opt.asText();
                                        ObjectNode optObj = dataSource.addObject();
                                        optObj.put("id", optVal.toLowerCase().replaceAll("[^a-z0-9_]", "_"));
                                        optObj.put("title", optVal);
                                    }
                                } else {
                                    ObjectNode defaultOpt = dataSource.addObject();
                                    defaultOpt.put("id", "agree");
                                    defaultOpt.put("title", "I agree to the terms");
                                }
                            }
                            case "TEXTAREA" -> {
                                ObjectNode textarea = formChildren.addObject();
                                textarea.put("type", "TextArea");
                                textarea.put("name", fieldName);
                                textarea.put("label", fieldLabel);
                                textarea.put("required", required);
                            }
                            default -> {
                                ObjectNode input = formChildren.addObject();
                                input.put("type", "TextInput");
                                input.put("name", fieldName);
                                input.put("label", fieldLabel);
                                input.put("required", required);
                                input.put("input-type", "text");
                            }
                        }
                    }
                }
            }

            // If no fields provided, add a default name input
            if (payloadFieldNames.isEmpty()) {
                ObjectNode defaultInput = formChildren.addObject();
                defaultInput.put("type", "TextInput");
                defaultInput.put("name", "full_name");
                defaultInput.put("label", "Your Full Name");
                defaultInput.put("required", true);
                payloadFieldNames.add("full_name");
            }

            // Footer / Submit Button
            ObjectNode footer = formChildren.addObject();
            footer.put("type", "Footer");
            footer.put("label", "Submit");
            ObjectNode action = footer.putObject("on-click-action");
            action.put("name", "complete");
            ObjectNode payload = action.putObject("payload");
            for (String fn : payloadFieldNames) {
                payload.put(fn, "${form." + fn + "}");
            }

            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            log.error("❌ [FlowSchemaBuilder] Failed to build Meta Flow JSON: {}", e.getMessage(), e);
            throw new RuntimeException("Flow JSON compilation error: " + e.getMessage());
        }
    }
}
