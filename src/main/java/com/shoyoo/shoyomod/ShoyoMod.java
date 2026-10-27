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
import net.minecraftforge.client.event.RenderGameOverlayEvent;
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
import org.lwjgl.input.Mouse;

import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.Random;

@Mod(modid = "mpmod", name = "MP-Mod", version = "1.0.1",
        acceptedMinecraftVersions = "[1.8.9]", clientSideOnly = true, acceptableRemoteVersions = "*")
public class ShoyoMod {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Random rnd = new Random();

    private static volatile boolean attack, place, breakBlocks, hud;
    private static final ArrayDeque<Long> clicks = new ArrayDeque<Long>();
    private static long totalClicks = 0L;
    private static String stateText = "";
    private static volatile int cpsMin = 6, cpsMax = 8;      // atacar
    private static volatile int pCpsMin = 6, pCpsMax = 8;     // colocar bloco
    private static Configuration cfg;

    private static Method clickMouse, rightClickMouse;
    private static KeyBinding kAttack, kPlace, kBreak;

    private long nextAttackNs = 0L, nextPlaceNs = 0L;

    @EventHandler
    public void preInit(FMLPreInitializationEvent e) {
        cfg = new Configuration(e.getSuggestedConfigurationFile());
        attack = cfg.getBoolean("attack", "macro", false, "Clicar sozinho para atacar (segurando o botao)");
        place = cfg.getBoolean("place", "macro", false, "Clicar sozinho para colocar bloco (segurando o botao)");
        breakBlocks = cfg.getBoolean("breakBlocks", "macro", false, "Continuar clicando mesmo com a mira num bloco");
        hud = cfg.getBoolean("hud", "macro", false, "Mostrar painel de CPS na tela");
        cpsMin = cfg.getInt("cpsMin", "macro", 6, 0, 20, "CPS minimo");
        cpsMax = cfg.getInt("cpsMax", "macro", 8, 0, 20, "CPS maximo");
        pCpsMin = cfg.getInt("placeCpsMin", "macro", 6, 0, 20, "CPS minimo de colocar bloco");
        pCpsMax = cfg.getInt("placeCpsMax", "macro", 8, 0, 20, "CPS maximo de colocar bloco");
        if (cfg.hasChanged()) cfg.save();
    }

    @EventHandler
    public void init(FMLInitializationEvent e) {
        clickMouse = ReflectionHelper.findMethod(Minecraft.class, mc, new String[]{"clickMouse", "func_147116_af"});
        rightClickMouse = ReflectionHelper.findMethod(Minecraft.class, mc, new String[]{"rightClickMouse", "func_147121_ag"});
        kAttack = new KeyBinding("Macro atacar", Keyboard.KEY_NONE, "MP-Mod");
        kPlace = new KeyBinding("Macro colocar bloco", Keyboard.KEY_NONE, "MP-Mod");
        kBreak = new KeyBinding("Macro quebrar bloco", Keyboard.KEY_NONE, "MP-Mod");
        ClientRegistry.registerKeyBinding(kAttack);
        ClientRegistry.registerKeyBinding(kPlace);
        ClientRegistry.registerKeyBinding(kBreak);
        MinecraftForge.EVENT_BUS.register(this);
        startLink();
        ClientCommandHandler.instance.registerCommand(new ShoyoCommand());
    }

    // ---- Ligacao com o SHOYO LAUNCHER (opcional): o launcher manda as configuracoes, o mod clica ----
    private static final int PORT_TO_LAUNCHER = 47653;
    private static final int PORT_FROM_LAUNCHER = 47654;

    private static void startLink() {
        Thread rx = new Thread(new Runnable() {
            public void run() {
                try {
                    DatagramSocket sock = new DatagramSocket(PORT_FROM_LAUNCHER, InetAddress.getByName("127.0.0.1"));
                    byte[] buf = new byte[128];
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    while (true) {
                        p.setLength(buf.length);
                        sock.receive(p);
                        applyRemote(new String(buf, 0, p.getLength(), "US-ASCII").trim());
                    }
                } catch (Throwable ignored) { }
            }
        }, "MP-Mod link rx");
        rx.setDaemon(true);
        rx.start();

        Thread hb = new Thread(new Runnable() {
            public void run() {
                try {
                    DatagramSocket tx = new DatagramSocket();
                    InetAddress lo = InetAddress.getByName("127.0.0.1");
                    byte[] b = "000".getBytes("US-ASCII");
                    while (true) {
                        tx.send(new DatagramPacket(b, b.length, lo, PORT_TO_LAUNCHER));
                        Thread.sleep(500);
                    }
                } catch (Throwable ignored) { }
            }
        }, "MP-Mod link hb");
        hb.setDaemon(true);
        hb.start();
    }

    /** "S atacar colocar break hud atkMin atkMax plcMin plcMax" */
    private static void applyRemote(String m) {
        try {
            String[] a = m.split(" ");
            if (a.length < 9 || !a[0].equals("S")) return;
            attack = a[1].equals("1");
            place = a[2].equals("1");
            breakBlocks = a[3].equals("1");
            hud = a[4].equals("1");
            cpsMin = Integer.parseInt(a[5]);
            cpsMax = Integer.parseInt(a[6]);
            pCpsMin = Integer.parseInt(a[7]);
            pCpsMax = Integer.parseInt(a[8]);
        } catch (Throwable ignored) { }
    }

    private static void save() {
        cfg.get("macro", "attack", false).set(attack);
        cfg.get("macro", "place", false).set(place);
        cfg.get("macro", "breakBlocks", false).set(breakBlocks);
        cfg.get("macro", "hud", false).set(hud);
        cfg.get("macro", "cpsMin", 6).set(cpsMin);
        cfg.get("macro", "cpsMax", 8).set(cpsMax);
        cfg.get("macro", "placeCpsMin", 6).set(pCpsMin);
        cfg.get("macro", "placeCpsMax", 8).set(pCpsMax);
        cfg.save();
    }

    private static void say(String s) {
        if (mc.thePlayer != null) mc.thePlayer.addChatMessage(new ChatComponentText("[MP-Mod] " + s));
    }

    private static String onOff(boolean b) { return b ? "ligado" : "desligado"; }

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent e) {
        if (kAttack.isPressed()) { attack = !attack; save(); say("atacar " + onOff(attack)); }
        if (kPlace.isPressed()) { place = !place; save(); say("colocar bloco " + onOff(place)); }
        if (kBreak.isPressed()) { breakBlocks = !breakBlocks; save(); say("quebrar bloco " + onOff(breakBlocks)); }
    }

    private long intervalNs(boolean forPlace) {
        int a = forPlace ? pCpsMin : cpsMin, b = forPlace ? pCpsMax : cpsMax;
        int hi = Math.max(a, b), lo = Math.min(a, b);
        double fast = 1000.0 / hi;
        double slow = 1000.0 / Math.max(lo, 1);
        double ms = slow <= fast ? fast : fast + rnd.nextDouble() * (slow - fast);
        return (long) (ms * 1_000_000.0);
    }

    @SubscribeEvent
    public void onRender(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;
        boolean ready = mc.thePlayer != null && mc.theWorld != null && mc.currentScreen == null;
        long now = System.nanoTime();

        // Estado do jogo: o que a mira esta vendo, se esta quebrando bloco ou usando item (comendo/bebendo)
        MovingObjectPosition mop = mc.objectMouseOver;
        boolean aimBlock = mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK;
        boolean aimEntity = mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY;
        boolean usingItem = ready && mc.thePlayer.isUsingItem();
        boolean mining = ready && mc.playerController != null && mc.playerController.getIsHittingBlock();
        stateText = "alvo: " + (aimBlock ? "bloco" : aimEntity ? "entidade" : "ar")
                + (mining ? " | quebrando" : "") + (usingItem ? " | usando item" : "");

        // ATACAR: segurando o botao. Nunca clica se estiver comendo/usando item, quebrando bloco ou
        // com a mira num bloco (a menos que "break" esteja ligado)
        boolean doAttack = ready && attack && Math.max(cpsMin, cpsMax) > 0 && isDown(mc.gameSettings.keyBindAttack)
                && !usingItem && (breakBlocks || (!aimBlock && !mining));
        if (!doAttack) {
            nextAttackNs = 0L; // condicoes falharam: reinicia o temporizador
        } else {
            if (nextAttackNs == 0L) nextAttackNs = now;
            if (now >= nextAttackNs) {
                nextAttackNs += intervalNs(false);
                if (nextAttackNs < now) nextAttackNs = now + intervalNs(false);
                invoke(clickMouse);
            }
        }

        // COLOCAR BLOCO: segurando o botao e com bloco na mao
        ItemStack held = ready ? mc.thePlayer.getHeldItem() : null;
        boolean doPlace = ready && place && Math.max(pCpsMin, pCpsMax) > 0 && isDown(mc.gameSettings.keyBindUseItem)
                && held != null && held.getItem() instanceof ItemBlock && !mc.thePlayer.isUsingItem();
        if (!doPlace) {
            nextPlaceNs = 0L;
        } else {
            if (nextPlaceNs == 0L) nextPlaceNs = now;
            if (now >= nextPlaceNs) {
                nextPlaceNs += intervalNs(true);
                if (nextPlaceNs < now) nextPlaceNs = now + intervalNs(true);
                invoke(rightClickMouse);
            }
        }
    }

    /** Estado do botao lido direto a cada quadro (sem esperar o tick de 50 ms do jogo): clique instantaneo ao apertar */
    private static boolean isDown(KeyBinding kb) {
        if (kb.isKeyDown()) return true;
        try {
            int code = kb.getKeyCode();
            return code < 0 ? Mouse.isButtonDown(code + 100) : (code > 0 && Keyboard.isKeyDown(code));
        } catch (Throwable t) {
            return false;
        }
    }

    private static void invoke(Method m) {
        try {
            m.invoke(mc);
            long t = System.currentTimeMillis();
            clicks.addLast(t);
            totalClicks++;
        } catch (Throwable ignored) { }
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post e) {
        if (!hud || e.type != RenderGameOverlayEvent.ElementType.ALL || mc.thePlayer == null) return;
        long now = System.currentTimeMillis();
        while (!clicks.isEmpty() && now - clicks.getFirst() > 1000) clicks.removeFirst();
        mc.fontRendererObj.drawStringWithShadow("CPS " + clicks.size() + "  |  total " + totalClicks, 4, 4, 0xFFFFFF);
        mc.fontRendererObj.drawStringWithShadow(stateText, 4, 16, 0xAAAAAA);
    }

    public static class ShoyoCommand extends CommandBase {
        @Override public String getCommandName() { return "shoyo"; }
        @Override public String getCommandUsage(ICommandSender s) {
            return "/shoyo attack|place|break|hud [on|off] | cps attack|place <min> <max> | status";
        }
        @Override public int getRequiredPermissionLevel() { return 0; }
        @Override public boolean canCommandSenderUseCommand(ICommandSender s) { return true; }

        @Override
        public void processCommand(ICommandSender sender, String[] a) {
            if (a.length == 0 || a[0].equalsIgnoreCase("status")) {
                say("atacar " + onOff(attack) + ", colocar " + onOff(place) + ", quebrar bloco " + onOff(breakBlocks)
                        + ", CPS atacar " + cpsMin + "-" + cpsMax + ", colocar " + pCpsMin + "-" + pCpsMax);
                return;
            }
            String k = a[0].toLowerCase();
            if (k.equals("cps") && a.length >= 4) {
                try {
                    boolean pl = a[1].equalsIgnoreCase("place");
                    int mn = Math.max(0, Math.min(20, Integer.parseInt(a[2])));
                    int mx = Math.max(0, Math.min(20, Integer.parseInt(a[3])));
                    if (pl) { pCpsMin = mn; pCpsMax = mx; } else { cpsMin = mn; cpsMax = mx; }
                    save();
                    say("CPS de " + (pl ? "colocar" : "atacar") + ": " + mn + "-" + mx);
                } catch (NumberFormatException ex) { say("use: /shoyo cps attack|place <min> <max> (0 a 20)"); }
                return;
            }
            if (k.equals("hud")) {
                hud = a.length >= 2 ? a[1].equalsIgnoreCase("on") : !hud;
                save();
                say("painel de CPS " + onOff(hud));
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
