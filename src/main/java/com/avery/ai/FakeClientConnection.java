package com.avery.ai;

import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.Packet;
import org.jetbrains.annotations.Nullable;

/** «Соединение» для бота: пакеты никуда не уходят, настоящей сети нет. */
public class FakeClientConnection extends ClientConnection {
	public FakeClientConnection(NetworkSide side) {
		super(side);
	}

	@Override
	public void tick() {}

	@Override
	public void disableAutoRead() {}

	@Override
	public void handleDisconnection() {}

	@Override
	public void send(Packet<?> packet) {}

	@Override
	public void send(Packet<?> packet, @Nullable PacketCallbacks callbacks) {}

	@Override
	public boolean isOpen() {
		return true;
	}
}
