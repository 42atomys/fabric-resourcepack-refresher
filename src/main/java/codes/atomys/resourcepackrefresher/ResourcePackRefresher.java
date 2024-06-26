package codes.atomys.resourcepackrefresher;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import codes.atomys.resourcepackrefresher.ResourcePackConfig.RPOption;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyrptonaught.kyrptconfig.config.ConfigManager;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.MinecraftServer.ServerResourcePackProperties;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.SendResourcePackTask;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;

public class ResourcePackRefresher implements ModInitializer {
  public static final String MOD_ID = "resourcepackrefresher";
  public static final Style SUCCESS_STYLE = Style.EMPTY.withColor(TextColor.fromFormatting(Formatting.GREEN));
  public static final Style ERROR_STYLE = Style.EMPTY.withColor(TextColor.fromFormatting(Formatting.RED));

  public static ConfigManager.SingleConfigManager configManager = new ConfigManager.SingleConfigManager(MOD_ID,
      new ResourcePackConfig());

  public static HashMap<String, ResourcePackConfig.RPOption> rpOptionHashMap = new HashMap<>();

  @Override
  public void onInitialize() {
    configManager.load();

    CommandRegistrationCallback.EVENT.register(ResourcePackRefresher::register);
    ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
      ResourcePackConfig.RPOption rpOption = requiredResourcepack();
      if (rpOption == null)
        return;

      System.out.println("[" + MOD_ID + "]: Sending resourcepack to " + handler.player.getName().getString());
      sendResourcePack(handler.player, ChecksumHelper.getMD5Checksum(rpOption.url));
    });

    if (getConfig().packs.size() == 0) {
      ResourcePackConfig.RPOption option = new ResourcePackConfig.RPOption();
      option.packname = "example_pack";
      option.url = "https://example.com/resourcepack.zip";
      getConfig().packs.add(option);
      configManager.save();
      System.out.println("[" + MOD_ID + "]: Generated example resourcepack config");
    }

    getConfig().packs.forEach(rpOption -> {
      rpOptionHashMap.put(rpOption.packname, rpOption);
    });
  }

  public static ResourcePackConfig getConfig() {
    return (ResourcePackConfig) configManager.getConfig();
  }

  private static RPOption requiredResourcepack() {
    final RPOption[] rpOption = { new RPOption() };

    getConfig().packs.forEach(rp -> {
      if (rp.required) {
        rpOption[0] = rp;
      }
    });

    return rpOption[0];
  }

  private static void register(CommandDispatcher<ServerCommandSource> dispatcher,
      CommandRegistryAccess commandRegistryAccess, CommandManager.RegistrationEnvironment registrationEnvironment) {
    LiteralArgumentBuilder<ServerCommandSource> cmd = CommandManager.literal("rpr")
        .requires((source) -> source.hasPermissionLevel(0));

    for (String packname : rpOptionHashMap.keySet()) {
      cmd.then(CommandManager.literal(packname)
          .then(CommandManager.argument("player", EntityArgumentType.players())
              .requires((source) -> source.hasPermissionLevel(2))
              .executes(commandContext -> execute(commandContext, packname,
                  EntityArgumentType.getPlayers(commandContext, "player"))))
          .executes(commandContext -> execute(commandContext, packname,
              Collections.singleton(commandContext.getSource().getPlayer()))));
    }
    dispatcher.register(cmd);
  }

  public static int execute(CommandContext<ServerCommandSource> commandContext, String packname,
      Collection<ServerPlayerEntity> players) {
    ResourcePackConfig.RPOption rpOption = rpOptionHashMap.get(packname);
    if (rpOption == null) {
      commandContext.getSource().sendFeedback(
          () -> Text.literal("Packname: ").append(packname).append(" was not found"),
          false);
      return 1;
    }

    String resourcePackMd5 = ChecksumHelper.getMD5Checksum(rpOption.url);
    if (resourcePackMd5.isEmpty()) {
      commandContext.getSource().sendFeedback(() -> Text.literal("Failed to send pack: ").append(rpOption.packname).append(". The URL ").append(
        Text.literal(rpOption.url).setStyle(Style.EMPTY.withColor(TextColor.fromFormatting(Formatting.GOLD)))
      ).append(" are not a valid file or website are unreachable").setStyle(ERROR_STYLE), false);
      return 1;
    }

    players.forEach(player -> {
      sendResourcePack(player, resourcePackMd5);
    });
    commandContext.getSource().sendFeedback(() -> Text.literal("Enabled pack: ").append(rpOption.packname), false);
    return 1;
  }

  private static void sendResourcePack(ServerPlayerEntity player, String checksum) {
    ResourcePackConfig.RPOption rpOption = requiredResourcepack();
    if (rpOption == null) {
      return;
    }

    SendResourcePackTask sendResourcePackTask = new SendResourcePackTask(new ServerResourcePackProperties(
      UUID.randomUUID(),
      rpOption.url + "?v=" + Instant.now().toString(),
      checksum,
      rpOption.required,
      rpOption.hasPrompt ? Text.literal(rpOption.message) : null
    ));

    sendResourcePackTask.sendPacket(packet -> player.networkHandler.sendPacket(packet));
  }
}
