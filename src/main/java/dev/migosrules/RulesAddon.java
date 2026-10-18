package dev.migosrules;
import java.util.function.Consumer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
public final class RulesAddon implements Consumer<Screen> {
    @Override public void accept(Screen parent) {
        RulesRepository.load();
        MinecraftClient.getInstance().setScreen(new RulesScreen(parent));
    }
}
