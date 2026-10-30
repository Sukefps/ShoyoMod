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
 * MP-Mod: so DETECTA. Nao clica, nao desenha nada na tela e nao mexe no jogo.
 * Avisa o SHOYO LAUNCHER (UDP local, porta 47653) com 4 caracteres:
 *   1: mirando num bloco | 2: bloco na mao | 3: comendo/bebendo/usando item (ou comida/pocao/arco na mao) | 4: quebrando bloco.
 * Quem da os cliques e o launcher; ele pausa quando voce mira num bloco, quebra bloco ou come.
 */
@Mod(modid = "mpmod", name = "MP-Mod", version = "1.0.1",
        acceptedMinecraftVersions = "[1.8.9]", clientSideOnly = true, acceptableRemoteVersions = "*")
public class ShoyoMod {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int PORT_TO_LAUNCHER = 47653;
    private static volatile String stateCode = "0000";

    @EventHandler
    public void init(FMLInitializationEvent e) {
        MinecraftForge.EVENT_BUS.register(this);
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
}
