package de.nofelix.stormboundisles.mixin.client;

import de.nofelix.stormboundisles.client.ScoreboardTabHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to control scoreboard rendering based on TAB key state.
 * <p>
 * This mixin intercepts the scoreboard rendering in the InGameHud
 * and only allows it to render when TAB is pressed.
 */
@Mixin(InGameHud.class)
public class ScoreboardRenderMixin {

    /**
     * Cancels scoreboard rendering when TAB is not pressed.
     * 
     * @param context     The draw context for rendering
     * @param tickCounter The render tick counter
     * @param ci          Callback info for canceling the method
     */
    @Inject(method = "renderScoreboardSidebar", at = @At("HEAD"), cancellable = true)
    private void onRenderScoreboardSidebar(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        // Only render scoreboard when TAB is pressed
        if (!ScoreboardTabHandler.isTabCurrentlyPressed()) {
            ci.cancel();
        }
    }
}
