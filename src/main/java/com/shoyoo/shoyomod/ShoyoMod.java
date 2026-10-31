package com.shoyoo.shoyomod;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * MP-Mod: DETECTA e, se o launcher pedir, liga o FAST PLACE (como no Lunar: tira a espera do clique direito).
 * Nao clica por conta propria e nao desenha nada na tela.
 * Avisa o SHOYO LAUNCHER (UDP local, porta 47653) com 4 caracteres:
 *   1: mirando num bloco | 2: bloco na mao | 3: comendo/bebendo/usando item (ou comida/pocao/arco na mao) | 4: quebrando bloco.
 * Quem da os cliques e o launcher; ele pausa quando voce mira num bloco, quebra bloco ou come.
 */
@Mod(modid = "mpmod", name = "MP-Mod", version = "1.0.1",
        acceptedMinecraftVersions = "[1.8.9]", clientSideOnly = true, acceptableRemoteVersions = "*")
public class ShoyoMod {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int PORT_TO_LAUNCHER = 47653;
    private static final int PORT_FROM_LAUNCHER = 47654;
    private static volatile String stateCode = "0000";
    private static volatile boolean fastPlace = false;
    private static volatile boolean noHitDelay = false;
    private static java.lang.reflect.Field clickCounterField;
    private static java.lang.reflect.Field delayField;

    @EventHandler
    public void init(FMLInitializationEvent e) {
        MinecraftForge.EVENT_BUS.register(this);
        Thread rx = new Thread(new Runnable() {
            public void run() {
                try {
                    DatagramSocket sock = new DatagramSocket(PORT_FROM_LAUNCHER, InetAddress.getByName("127.0.0.1"));
                    byte[] buf = new byte[64];
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    while (true) {
                        p.setLength(buf.length);
                        sock.receive(p);
                        String m = new String(buf, 0, p.getLength(), "US-ASCII").trim();
                        if (m.startsWith("F ")) {
                            // formato: "F <0|1> H <0|1>"
                            fastPlace = m.length() > 2 && m.charAt(2) == '1';
                            int h = m.indexOf(" H ");
                            noHitDelay = h >= 0 && h + 3 < m.length() && m.charAt(h + 3) == '1';
                        }
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
        }, "MP-Mod link");
        hb.setDaemon(true);
        hb.start();
    }

    @SubscribeEvent
    public void onRender(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;
        fastPlaceNow();
        boolean ready = mc.thePlayer != null && mc.theWorld != null && mc.currentScreen == null;
        if (!ready) { stateCode = "0000"; return; }
        MovingObjectPosition mop = mc.objectMouseOver;
        boolean aimBlock = mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK;
        boolean usingItem = mc.thePlayer.isUsingItem();
        boolean mining = mc.playerController != null && mc.playerController.getIsHittingBlock();
        ItemStack held = mc.thePlayer.getHeldItem();
        boolean holdsBlock = held != null && held.getItem() instanceof ItemBlock;
        boolean usingAny = usingItem || (held != null && (held.getItem() instanceof ItemFood
                || held.getItem() instanceof ItemPotion || held.getItem() instanceof ItemBow));
        stateCode = (aimBlock ? "1" : "0") + (holdsBlock ? "1" : "0") + (usingAny ? "1" : "0") + (mining ? "1" : "0");
    }

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

    /** Fast place: com bloco na mao e o botao de usar segurado, zera a espera entre colocacoes (o jogo coloca pelo caminho normal). */
    private static java.lang.reflect.Field clickCounterField() {
        if (clickCounterField != null) return clickCounterField;
        for (String n : new String[]{"leftClickCounter", "field_71429_W"}) {
            try {
                java.lang.reflect.Field f = Minecraft.class.getDeclaredField(n);
                f.setAccessible(true);
                clickCounterField = f;
                return f;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static void fastPlaceNow() {
        if (!fastPlace && !noHitDelay) return;
        if (mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null) return;
        if (noHitDelay && mc.gameSettings.keyBindAttack.isKeyDown()) {
            java.lang.reflect.Field c = clickCounterField();
            if (c != null) {
                try { if (c.getInt(mc) > 0) c.setInt(mc, 0); } catch (Throwable ignored) { }
            }
        }
        if (!mc.gameSettings.keyBindUseItem.isKeyDown()) return;
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock) || mc.thePlayer.isUsingItem()) return;
        java.lang.reflect.Field f = delayField();
        if (f == null) return;
        try {
            if (f.getInt(mc) > 0) f.setInt(mc, 0);
        } catch (Throwable ignored) { }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;
        fastPlaceNow();
    }
}
