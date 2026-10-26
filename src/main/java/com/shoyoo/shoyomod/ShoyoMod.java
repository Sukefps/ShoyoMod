package com.shoyoo.shoyomod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.lwjgl.input.Keyboard;

import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Random;

@Mod(modid = "shoyomacro", name = "Shoyo Macro", version = "1.0.1",
        acceptedMinecraftVersions = "[1.8.9]", clientSideOnly = true, acceptableRemoteVersions = "*")
public class ShoyoMod {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Random rnd = new Random();

    private static boolean attack, place, breakBlocks;
    private static int cpsMin = 6, cpsMax = 8;
    private static Configuration cfg;

    private static Method clickMouse, rightClickMouse;
    private static KeyBinding kAttack, kPlace, kBreak;

    private long nextAttackNs = 0L, nextPlaceNs = 0L;

    // Sinal para o SHOYO LAUNCHER: '1' = mira num bloco, '0' = nao. Enviado so ao mudar + batida a cada 500 ms.
    private static DatagramSocket sock;
    private static InetAddress loop;
    private char lastSent = 'x';
    private long lastSendMs = 0L;
    private static final int LAUNCHER_PORT = 47653;

    private void sendAim(boolean block) {
        char c = block ? '1' : '0';
        long t = System.currentTimeMillis();
        if (c == lastSent && t - lastSendMs < 500) return;
        try {
            if (sock == null) { sock = new DatagramSocket(); loop = InetAddress.getByName("127.0.0.1"); }
            byte[] b = new byte[]{(byte) c};
            sock.send(new DatagramPacket(b, 1, loop, LAUNCHER_PORT));
        } catch (Throwable ignored) { }
        lastSent = c;
        lastSendMs = t;
    }

    @EventHandler
    public void preInit(FMLPreInitializationEvent e) {
        cfg = new Configuration(e.getSuggestedConfigurationFile());
        attack = cfg.getBoolean("attack", "macro", false, "Clicar sozinho para atacar (segurando o botao)");
        place = cfg.getBoolean("place", "macro", false, "Clicar sozinho para colocar bloco (segurando o botao)");
        breakBlocks = cfg.getBoolean("breakBlocks", "macro", false, "Continuar clicando mesmo com a mira num bloco");
        cpsMin = cfg.getInt("cpsMin", "macro", 6, 0, 20, "CPS minimo");
        cpsMax = cfg.getInt("cpsMax", "macro", 8, 0, 20, "CPS maximo");
        if (cfg.hasChanged()) cfg.save();
    }

    @EventHandler
    public void init(FMLInitializationEvent e) {
        clickMouse = ReflectionHelper.findMethod(Minecraft.class, mc, new String[]{"clickMouse", "func_147116_af"});
        rightClickMouse = ReflectionHelper.findMethod(Minecraft.class, mc, new String[]{"rightClickMouse", "func_147121_ag"});
        kAttack = new KeyBinding("Macro atacar", Keyboard.KEY_NONE, "Shoyo Macro");
        kPlace = new KeyBinding("Macro colocar bloco", Keyboard.KEY_NONE, "Shoyo Macro");
        kBreak = new KeyBinding("Macro quebrar bloco", Keyboard.KEY_NONE, "Shoyo Macro");
        ClientRegistry.registerKeyBinding(kAttack);
        ClientRegistry.registerKeyBinding(kPlace);
        ClientRegistry.registerKeyBinding(kBreak);
        MinecraftForge.EVENT_BUS.register(this);
        ClientCommandHandler.instance.registerCommand(new ShoyoCommand());
    }

    private static void save() {
        cfg.get("macro", "attack", false).set(attack);
        cfg.get("macro", "place", false).set(place);
        cfg.get("macro", "breakBlocks", false).set(breakBlocks);
        cfg.get("macro", "cpsMin", 6).set(cpsMin);
        cfg.get("macro", "cpsMax", 8).set(cpsMax);
        cfg.save();
    }

    private static void say(String s) {
        if (mc.thePlayer != null) mc.thePlayer.addChatMessage(new ChatComponentText("[Shoyo] " + s));
    }

    private static String onOff(boolean b) { return b ? "ligado" : "desligado"; }

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent e) {
        if (kAttack.isPressed()) { attack = !attack; save(); say("atacar " + onOff(attack)); }
        if (kPlace.isPressed()) { place = !place; save(); say("colocar bloco " + onOff(place)); }
        if (kBreak.isPressed()) { breakBlocks = !breakBlocks; save(); say("quebrar bloco " + onOff(breakBlocks)); }
    }

    private long intervalNs() {
        int hi = Math.max(cpsMin, cpsMax), lo = Math.min(cpsMin, cpsMax);
        double fast = 1000.0 / hi;
        double slow = 1000.0 / Math.max(lo, 1);
        double ms = slow <= fast ? fast : fast + rnd.nextDouble() * (slow - fast);
        return (long) (ms * 1_000_000.0);
    }

    @SubscribeEvent
    public void onRender(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;
        boolean ready = mc.thePlayer != null && mc.theWorld != null && mc.currentScreen == null
                && mc.inGameHasFocus && Math.max(cpsMin, cpsMax) > 0;
        long now = System.nanoTime();

        // ATACAR: segurando o botao; com a mira num bloco deixa o jogo quebrar normal (a menos que "break" ligado)
        boolean aimBlock = mc.objectMouseOver != null
                && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK;
        sendAim(ready && aimBlock);
        boolean doAttack = ready && attack && mc.gameSettings.keyBindAttack.isKeyDown()
                && (breakBlocks || !aimBlock);
        if (!doAttack) {
            nextAttackNs = 0L; // condicoes falharam: reinicia o temporizador
        } else {
            if (nextAttackNs == 0L) nextAttackNs = now;
            if (now >= nextAttackNs) {
                nextAttackNs += intervalNs();
                if (nextAttackNs < now) nextAttackNs = now + intervalNs();
                invoke(clickMouse);
            }
        }

        // COLOCAR BLOCO: segurando o botao e com bloco na mao
        ItemStack held = ready ? mc.thePlayer.getHeldItem() : null;
        boolean doPlace = ready && place && mc.gameSettings.keyBindUseItem.isKeyDown()
                && held != null && held.getItem() instanceof ItemBlock && !mc.thePlayer.isUsingItem();
        if (!doPlace) {
            nextPlaceNs = 0L;
        } else {
            if (nextPlaceNs == 0L) nextPlaceNs = now;
            if (now >= nextPlaceNs) {
                nextPlaceNs += intervalNs();
                if (nextPlaceNs < now) nextPlaceNs = now + intervalNs();
                invoke(rightClickMouse);
            }
        }
    }

    private static void invoke(Method m) {
        try { m.invoke(mc); } catch (Throwable ignored) { }
    }

    public static class ShoyoCommand extends CommandBase {
        @Override public String getCommandName() { return "shoyo"; }
        @Override public String getCommandUsage(ICommandSender s) {
            return "/shoyo attack|place|break [on|off] | cps <min> <max> | status";
        }
        @Override public int getRequiredPermissionLevel() { return 0; }
        @Override public boolean canCommandSenderUseCommand(ICommandSender s) { return true; }

        @Override
        public void processCommand(ICommandSender sender, String[] a) {
            if (a.length == 0 || a[0].equalsIgnoreCase("status")) {
                say("atacar " + onOff(attack) + ", colocar " + onOff(place) + ", quebrar bloco " + onOff(breakBlocks)
                        + ", CPS " + cpsMin + "-" + cpsMax);
                return;
            }
            String k = a[0].toLowerCase();
            if (k.equals("cps") && a.length >= 3) {
                try {
                    cpsMin = Math.max(0, Math.min(20, Integer.parseInt(a[1])));
                    cpsMax = Math.max(0, Math.min(20, Integer.parseInt(a[2])));
                    save();
                    say("CPS " + cpsMin + "-" + cpsMax);
                } catch (NumberFormatException ex) { say("use numeros de 0 a 20"); }
                return;
            }
            if (k.equals("attack") || k.equals("place") || k.equals("break")) {
                boolean cur = k.equals("attack") ? attack : k.equals("place") ? place : breakBlocks;
                boolean val = a.length >= 2 ? a[1].equalsIgnoreCase("on") : !cur;
                if (k.equals("attack")) attack = val; else if (k.equals("place")) place = val; else breakBlocks = val;
                save();
                say(k + " " + onOff(val));
                return;
            }
            say(getCommandUsage(sender));
        }
    }
}
