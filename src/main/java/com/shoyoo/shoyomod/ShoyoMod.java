package com.shoyoo.shoyomod;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.KeyBinding;
import java.io.IOException;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemPotion;
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
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
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

    private static volatile boolean attack, place, breakBlocks, hud, needEntity;
    private static final ArrayDeque<Long> clicks = new ArrayDeque<Long>();
    private static long totalClicks = 0L;
    private static String stateText = "";
    private static volatile long joinedAtMs = 0L;
    // estado enviado ao launcher: mira em bloco / bloco na mao / comendo-usando item / quebrando bloco
    private static volatile String stateCode = "0000";
    private static volatile int radius = 6;          // distancia (blocos) para considerar uma entidade "perto"
    private boolean nearCached = false;
    private long nearCheckMs = 0L;
    private static volatile int cpsMin = 6, cpsMax = 8;      // atacar
    private static volatile int pCpsMin = 6, pCpsMax = 8;     // colocar bloco
    private static Configuration cfg;

    private static Method clickMouse;
    private static KeyBinding kAttack, kPlace, kBreak, kGui;

    private long nextAttackNs = 0L, nextPlaceNs = 0L;

    @EventHandler
    public void preInit(FMLPreInitializationEvent e) {
        cfg = new Configuration(e.getSuggestedConfigurationFile());
        attack = cfg.getBoolean("attack", "macro", false, "Clicar sozinho para atacar (segurando o botao)");
        place = cfg.getBoolean("place", "macro", false, "Clicar sozinho para colocar bloco (segurando o botao)");
        breakBlocks = cfg.getBoolean("breakBlocks", "macro", false, "Continuar clicando mesmo com a mira num bloco");
        needEntity = cfg.getBoolean("needEntity", "macro", false, "So clicar (atacar) com entidade perto");
        radius = cfg.getInt("radius", "macro", 6, 1, 20, "Distancia (blocos) para uma entidade contar como perto");
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
                kAttack = new KeyBinding("Macro atacar", Keyboard.KEY_NONE, "MP-Mod");
        kPlace = new KeyBinding("Macro colocar bloco", Keyboard.KEY_NONE, "MP-Mod");
        kBreak = new KeyBinding("Macro quebrar bloco", Keyboard.KEY_NONE, "MP-Mod");
        kGui = new KeyBinding("Abrir menu", Keyboard.KEY_L, "MP-Mod");
        ClientRegistry.registerKeyBinding(kGui);
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
                    while (true) {
                        byte[] b = stateCode.getBytes("US-ASCII");
                        tx.send(new DatagramPacket(b, b.length, lo, PORT_TO_LAUNCHER));
                        Thread.sleep(100);
                    }
                } catch (Throwable ignored) { }
            }
        }, "MP-Mod link hb");
        hb.setDaemon(true);
        hb.start();
    }

    /** "S atacar colocar break hud atkMin atkMax plcMin plcMax" */
    private static void applyRemote(String m) {
        if (true) return; // a tela do mod (tecla L) manda nas configuracoes
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
        cfg.get("macro", "radius", 6).set(radius);
        cfg.get("macro", "needEntity", false).set(needEntity);
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
    public void onJoin(EntityJoinWorldEvent e) {
        if (e.entity == mc.thePlayer) {
            joinedAtMs = System.currentTimeMillis();
        }
    }

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent e) {
        if (kGui.isPressed() && mc.currentScreen == null && mc.thePlayer != null) { mc.displayGuiScreen(new ShoyoGui()); return; }
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
        boolean near = ready && (aimEntity || entityNear());
        stateText = "alvo: " + (aimBlock ? "bloco" : aimEntity ? "entidade" : "ar")
                + (near ? " | entidade perto" : "") + (mining ? " | quebrando" : "") + (usingItem ? " | usando item" : "")
                + " | botao: " + (ready && isDown(mc.gameSettings.keyBindAttack) ? "apertado" : "solto")
                + " | atacar " + onOff(attack);

        ItemStack curItem = ready ? mc.thePlayer.getHeldItem() : null;
        boolean holdsBlock = curItem != null && curItem.getItem() instanceof ItemBlock;
        boolean usingAny = usingItem || (curItem != null && (curItem.getItem() instanceof ItemFood
                || curItem.getItem() instanceof ItemPotion || curItem.getItem() instanceof ItemBow));
        stateCode = ((ready && aimBlock) ? "1" : "0") + (holdsBlock ? "1" : "0") + (usingAny ? "1" : "0") + (mining ? "1" : "0");

        // ATACAR: clica sem parar enquanto voce segura o botao (com ou sem entidade perto; se "entity" estiver ligado, so com entidade perto).
        // Quebrando bloco, com a mira num bloco ou comendo: nao clica (quebra/come normal).
        boolean doAttack = ready && attack && Math.max(cpsMin, cpsMax) > 0 && isDown(mc.gameSettings.keyBindAttack)
                && (!needEntity || near) && !usingItem && (breakBlocks || (!aimBlock && !mining));
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
    }

    // ---- COLOCAR BLOCO RAPIDO: nao clica; so encurta o tempo de espera do clique direito do proprio jogo.
    // Quem coloca o bloco e o jogo, pelo caminho normal (compativel com agachar/safe walk). ----
    private static java.lang.reflect.Field delayField;
    private int placeTarget = 4;

    private static java.lang.reflect.Field delayField() {
        if (delayField != null) return delayField;
        for (String n : new String[]{"rightClickDelayTimer", "field_71467_ac"}) {
            try {
                java.lang.reflect.Field f = Minecraft.class.getDeclaredField(n);
                f.setAccessible(true);
                delayField = f;
                return f;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private int pickPlaceDelayTicks() {
        int hi = Math.max(pCpsMin, pCpsMax), lo = Math.max(1, Math.min(pCpsMin, pCpsMax));
        double cps = lo + rnd.nextDouble() * (hi - lo);
        double t = 20.0 / Math.max(cps, 1.0);
        int n = (int) Math.floor(t);
        if (rnd.nextDouble() < t - n) n++;
        return Math.max(1, Math.min(4, n));
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;
        if (!place || Math.max(pCpsMin, pCpsMax) <= 0 || mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null) return;
        if (!mc.gameSettings.keyBindUseItem.isKeyDown()) return;
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock) || mc.thePlayer.isUsingItem()) return;
        java.lang.reflect.Field f = delayField();
        if (f == null) return;
        try {
            int cur = f.getInt(mc);
            if (cur == 0) {            // o jogo vai colocar neste tick: sorteia o proximo intervalo
                placeTarget = pickPlaceDelayTicks();
                clicks.addLast(System.currentTimeMillis());
                totalClicks++;
            } else if (cur > placeTarget) {
                f.setInt(mc, placeTarget);
            }
        } catch (Throwable ignored) { }
    }

    /** Tem mob/jogador vivo (nao armor stand) a ate "radius" blocos? Verificado a cada 100 ms. */
    private boolean entityNear() {
        long t = System.currentTimeMillis();
        if (t - nearCheckMs < 100) return nearCached;
        nearCheckMs = t;
        boolean found = false;
        try {
            for (Object o : mc.theWorld.loadedEntityList) {
                Entity en = (Entity) o;
                if (en == mc.thePlayer || en.isDead || !(en instanceof EntityLivingBase) || en instanceof EntityArmorStand) continue;
                if (en.getDistanceToEntity(mc.thePlayer) <= radius) { found = true; break; }
            }
        } catch (Throwable ignored) { }
        nearCached = found;
        return found;
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
        mc.fontRendererObj.drawStringWithShadow("MP-Mod 1.0.1  |  CPS " + clicks.size() + "  |  total " + totalClicks, 4, 4, 0xFFFFFF);
        mc.fontRendererObj.drawStringWithShadow(stateText, 4, 16, 0xAAAAAA);
    }

    /** Tela do MP-Mod (tecla L): liga/desliga atacar (esquerdo) e colocar bloco (direito) e muda o CPS. */
    public static class ShoyoGui extends GuiScreen {
        private GuiButton bAtk, bPlc;
        private GuiButton[] vals = new GuiButton[4]; // atkMin, atkMax, plcMin, plcMax

        @Override
        public void initGui() {
            buttonList.clear();
            int cx = width / 2, y = height / 2 - 78;
            bAtk = new GuiButton(0, cx - 100, y, 200, 20, "");
            buttonList.add(bAtk);
            addRow(1, y + 24, 0);
            addRow(3, y + 48, 1);
            bPlc = new GuiButton(5, cx - 100, y + 78, 200, 20, "");
            buttonList.add(bPlc);
            addRow(6, y + 102, 2);
            addRow(8, y + 126, 3);
            buttonList.add(new GuiButton(10, cx - 100, y + 156, 200, 20, "Pronto"));
            refresh();
        }

        private void addRow(int id, int y, int slot) {
            int cx = width / 2;
            buttonList.add(new GuiButton(id, cx - 100, y, 20, 20, "-"));
            vals[slot] = new GuiButton(100 + slot, cx - 78, y, 156, 20, "");
            vals[slot].enabled = false;
            buttonList.add(vals[slot]);
            buttonList.add(new GuiButton(id + 1, cx + 80, y, 20, 20, "+"));
        }

        private void refresh() {
            bAtk.displayString = "Atacar (botao esquerdo): " + (attack ? "LIGADO" : "desligado");
            bPlc.displayString = "Colocar bloco (botao direito): " + (place ? "LIGADO" : "desligado");
            vals[0].displayString = "CPS minimo: " + cpsMin;
            vals[1].displayString = "CPS maximo: " + cpsMax;
            vals[2].displayString = "CPS minimo: " + pCpsMin;
            vals[3].displayString = "CPS maximo: " + pCpsMax;
        }

        private static int clamp(int v) { return Math.max(0, Math.min(20, v)); }

        @Override
        protected void actionPerformed(GuiButton b) throws IOException {
            switch (b.id) {
                case 0: attack = !attack; break;
                case 1: cpsMin = clamp(cpsMin - 1); break;
                case 2: cpsMin = clamp(cpsMin + 1); if (cpsMax < cpsMin) cpsMax = cpsMin; break;
                case 3: cpsMax = clamp(cpsMax - 1); if (cpsMax < cpsMin) cpsMin = cpsMax; break;
                case 4: cpsMax = clamp(cpsMax + 1); break;
                case 5: place = !place; break;
                case 6: pCpsMin = clamp(pCpsMin - 1); break;
                case 7: pCpsMin = clamp(pCpsMin + 1); if (pCpsMax < pCpsMin) pCpsMax = pCpsMin; break;
                case 8: pCpsMax = clamp(pCpsMax - 1); if (pCpsMax < pCpsMin) pCpsMin = pCpsMax; break;
                case 9: pCpsMax = clamp(pCpsMax + 1); break;
                case 10: save(); mc.displayGuiScreen(null); return;
                default: return;
            }
            save();
            refresh();
        }

        @Override
        protected void keyTyped(char c, int code) throws IOException {
            if (code == kGui.getKeyCode()) { save(); mc.displayGuiScreen(null); return; }
            super.keyTyped(c, code);
        }

        @Override
        public void drawScreen(int mx, int my, float pt) {
            drawDefaultBackground();
            drawCenteredString(fontRendererObj, "MP-Mod", width / 2, height / 2 - 100, 0xFFFFFF);
            super.drawScreen(mx, my, pt);
        }

        @Override
        public boolean doesGuiPauseGame() { return false; }
    }

    public static class ShoyoCommand extends CommandBase {
        @Override public String getCommandName() { return "shoyo"; }
        @Override public String getCommandUsage(ICommandSender s) {
            return "/shoyo attack|place|break|hud|entity [on|off] | radius <1-20> | cps attack|place <min> <max> | status";
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
            if (k.equals("entity")) {
                needEntity = a.length >= 2 ? a[1].equalsIgnoreCase("on") : !needEntity;
                save();
                say("so clicar com entidade perto: " + onOff(needEntity));
                return;
            }
            if (k.equals("radius") && a.length >= 2) {
                try {
                    radius = Math.max(1, Math.min(20, Integer.parseInt(a[1])));
                    save();
                    say("entidade perto = ate " + radius + " blocos");
                } catch (NumberFormatException ex) { say("use: /shoyo radius <1 a 20>"); }
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
