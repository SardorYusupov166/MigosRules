package dev.chatsorter.mixin;

import dev.chatsorter.ChatSorter;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;
import dev.chatsorter.ChatPosition;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.font.TextRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatHud.class)
public abstract class ChatHudMixin {
    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/font/TextRenderer;IIIZZ)V", at = @At("HEAD"))
    private void chatsorter$translate(DrawContext context, TextRenderer textRenderer, int currentTick, int mouseX, int mouseY, boolean focused, boolean chatHidden, CallbackInfo ci) {
        context.getMatrices().pushMatrix();
        context.getMatrices().translate(ChatPosition.x, ChatPosition.y);
    }
    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/font/TextRenderer;IIIZZ)V", at = @At("RETURN"))
    private void chatsorter$restore(DrawContext context, TextRenderer textRenderer, int currentTick, int mouseX, int mouseY, boolean focused, boolean chatHidden, CallbackInfo ci) {
        context.getMatrices().popMatrix();
    }
    @Inject(method = "addMessage(Lnet/minecraft/text/Text;)V", at = @At("HEAD"), cancellable = true)
    private void chatsorter$sort(Text message, CallbackInfo ci) {
        if (!ChatSorter.capture(message)) ci.cancel();
    }
}
