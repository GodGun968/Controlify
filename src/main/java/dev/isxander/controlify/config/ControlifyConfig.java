package dev.isxander.controlify.config;

import com.google.gson.*;
import dev.isxander.controlify.Controlify;
import dev.isxander.controlify.controller.Controller;
import dev.isxander.controlify.controller.joystick.CompoundJoystickInfo;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ControlifyConfig {
    public static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("controlify.json");

    private static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .setPrettyPrinting()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerTypeHierarchyAdapter(Class.class, new ClassTypeAdapter())
            .create();

    private final Controlify controlify;

    private String currentControllerUid;
    private JsonObject controllerData = new JsonObject();
    private Map<String, CompoundJoystickInfo> compoundJoysticks = Map.of();
    private GlobalSettings globalSettings = new GlobalSettings();
    private boolean firstLaunch;

    private boolean dirty;

    public ControlifyConfig(Controlify controlify) {
        this.controlify = controlify;
    }

    public void save() {
        Controlify.LOGGER.info("Saving Controlify config...");

        try {
            Files.deleteIfExists(CONFIG_PATH);
            Files.writeString(CONFIG_PATH, GSON.toJson(generateConfig()), StandardOpenOption.CREATE_NEW, StandardOpenOption.TRUNCATE_EXISTING);
            dirty = false;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save config!", e);
        }
    }

    public void load() {
        Controlify.LOGGER.info("Loading Controlify config...");

        if (!Files.exists(CONFIG_PATH)) {
            firstLaunch = true;
            save();
            return;
        }

        try {
            applyConfig(GSON.fromJson(Files.readString(CONFIG_PATH), JsonObject.class));
        } catch (Exception e) {
            Controlify.LOGGER.error("Failed to load Controlify config!", e);
        }

        if (dirty) {
            Controlify.LOGGER.info("Config was dirty after load, saving...");
            save();
        }
    }

    private JsonObject generateConfig() {
        JsonObject config = new JsonObject();

        JsonObject newControllerData = controllerData.deepCopy(); // we use the old config, so we don't lose disconnected controller data

        for (var controller : Controller.CONTROLLERS.values()) {
            // `add` replaces if already existing
            newControllerData.add(controller.uid(), generateControllerConfig(controller));
        }

        controllerData = newControllerData;
        config.addProperty("current_controller", currentControllerUid = controlify.currentController().uid());
        config.add("controllers", controllerData);
        config.add("compound_joysticks", GSON.toJsonTree(compoundJoysticks.values().toArray(new CompoundJoystickInfo[0])));
        config.add("global", GSON.toJsonTree(globalSettings));

        return config;
    }

    private JsonObject generateControllerConfig(Controller<?, ?> controller) {
        JsonObject object = new JsonObject();

        object.add("config", GSON.toJsonTree(controller.config()));
        object.add("bindings", controller.bindings().toJson());

        return object;
    }

    private void applyConfig(JsonObject object) {
        globalSettings = GSON.fromJson(object.getAsJsonObject("global"), GlobalSettings.class);
        if (globalSettings == null) {
            globalSettings = new GlobalSettings();
            setDirty();
        }

        JsonObject controllers = object.getAsJsonObject("controllers");
        if (controllers != null) {
            this.controllerData = controllers;
            for (var controller : Controller.CONTROLLERS.values()) {
                loadOrCreateControllerData(controller);
            }
        } else {
            setDirty();
        }

        this.compoundJoysticks = object
                .getAsJsonArray("compound_joysticks")
                .asList()
                .stream()
                .map(element -> GSON.fromJson(element, CompoundJoystickInfo.class))
                .collect(Collectors.toMap(info -> info.type().identifier(), Function.identity()));

        if (object.has("current_controller")) {
            currentControllerUid = object.get("current_controller").getAsString();
        } else {
            currentControllerUid = controlify.currentController().uid();
            setDirty();
        }
    }

    public void loadOrCreateControllerData(Controller<?, ?> controller) {
        var uid = controller.uid();
        if (controllerData.has(uid)) {
            Controlify.LOGGER.info("Loading controller data for " + uid);
            applyControllerConfig(controller, controllerData.getAsJsonObject(uid));
        } else {
            Controlify.LOGGER.info("New controller found, creating controller data for " + uid);
            setDirty();
        }
    }

    private void applyControllerConfig(Controller<?, ?> controller, JsonObject object) {
        try {
            controller.setConfig(GSON, object.getAsJsonObject("config"));
            dirty |= !controller.bindings().fromJson(object.getAsJsonObject("bindings"));
        } catch (Exception e) {
            Controlify.LOGGER.error("Failed to load controller data for " + controller.uid() + ". Resetting to default!", e);
            controller.resetConfig();
            save();
        }
    }

    public void setDirty() {
        dirty = true;
    }

    public void saveIfDirty() {
        if (dirty) {
            Controlify.LOGGER.info("Config is dirty. Saving...");
            save();
        }
    }

    public Map<String, CompoundJoystickInfo> getCompoundJoysticks() {
        return compoundJoysticks;
    }

    public GlobalSettings globalSettings() {
        return globalSettings;
    }

    public boolean isFirstLaunch() {
        return firstLaunch;
    }

    public String currentControllerUid() {
        return currentControllerUid;
    }
}
