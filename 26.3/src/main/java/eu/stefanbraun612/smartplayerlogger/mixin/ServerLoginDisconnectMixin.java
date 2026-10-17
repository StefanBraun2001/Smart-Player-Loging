package eu.stefanbraun612.smartplayerlogger.mixin;

import eu.stefanbraun612.smartplayerlogger.LoginNameResolver;
import eu.stefanbraun612.smartplayerlogger.SmartPlayerLogger;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Every refusal during the login phase (whitelist, ban, server full, already logged in, failed
// authentication, login timeout) ends in this method with the reason as a Component, which
// makes it the one place that sees both who was refused and why. require = 0: if a future
// version renames it, refused-connection logging quietly stops instead of the server failing to start.
@Mixin(ServerLoginPacketListenerImpl.class)
public class ServerLoginDisconnectMixin {
	@Inject(method = "disconnect(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), require = 0)
	private void smartplayerlogger$onLoginDisconnect(Component reason, CallbackInfo ci) {
		SmartPlayerLogger.reportRefusal(LoginNameResolver.resolve(this), reason == null ? null : reason.getString());
	}
}
