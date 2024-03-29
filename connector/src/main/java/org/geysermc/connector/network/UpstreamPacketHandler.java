/*
 * Copyright (c) 2019-2020 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.connector.network;

import com.nukkitx.protocol.bedrock.BedrockPacket;
import com.nukkitx.protocol.bedrock.packet.*;
import org.geysermc.connector.common.AuthType;
import org.geysermc.connector.configuration.GeyserConfiguration;
import org.geysermc.connector.GeyserConnector;
import org.geysermc.connector.network.addon.FormAddonListener;
import org.geysermc.connector.network.session.GeyserSession;
import org.geysermc.connector.network.translators.PacketTranslatorRegistry;
import org.geysermc.connector.utils.LoginEncryptionUtils;
import org.geysermc.connector.utils.LanguageUtils;

public class UpstreamPacketHandler extends LoggingPacketHandler {

    public UpstreamPacketHandler(GeyserConnector connector, GeyserSession session) {
        super(connector, session);
    }

    private boolean translateAndDefault(BedrockPacket packet) {
        return PacketTranslatorRegistry.BEDROCK_TRANSLATOR.translate(packet.getClass(), packet, session);
    }

    @Override
    public boolean handle(LoginPacket loginPacket) {
        if (loginPacket.getProtocolVersion() > GeyserConnector.BEDROCK_PACKET_CODEC.getProtocolVersion()) {
            // Too early to determine session locale
            session.disconnect(LanguageUtils.getLocaleStringLog("geyser.network.outdated.server", GeyserConnector.BEDROCK_PACKET_CODEC.getMinecraftVersion()));
            return true;
        } else if (loginPacket.getProtocolVersion() < GeyserConnector.BEDROCK_PACKET_CODEC.getProtocolVersion()) {
            session.disconnect(LanguageUtils.getLocaleStringLog("geyser.network.outdated.client", GeyserConnector.BEDROCK_PACKET_CODEC.getMinecraftVersion()));
            return true;
        }

        LoginEncryptionUtils.encryptPlayerConnection(connector, session, loginPacket);

        PlayStatusPacket playStatus = new PlayStatusPacket();
        playStatus.setStatus(PlayStatusPacket.Status.LOGIN_SUCCESS);
        session.sendUpstreamPacket(playStatus);

        ResourcePacksInfoPacket resourcePacksInfo = new ResourcePacksInfoPacket();
        session.sendUpstreamPacket(resourcePacksInfo);
        return true;
    }

    @Override
    public boolean handle(ResourcePackClientResponsePacket packet) {
        switch (packet.getStatus()) {
            case COMPLETED:
                session.connect(connector.getRemoteServer());
                connector.getLogger().info(LanguageUtils.getLocaleStringLog("geyser.network.connect", session.getAuthData().getName()));
                break;
            case HAVE_ALL_PACKS:
                ResourcePackStackPacket stack = new ResourcePackStackPacket();
                stack.setExperimental(false);
                stack.setForcedToAccept(false);
                stack.setGameVersion("*");
                session.sendUpstreamPacket(stack);
                break;
            default:
                session.disconnect("disconnectionScreen.resourcePack");
                break;
        }

        return true;
    }

    @Override
    public boolean handle(ModalFormResponsePacket packet) {
        if (packet.getFormId() == LoginEncryptionUtils.AUTH_FORM_ID || packet.getFormId() == LoginEncryptionUtils.AUTH_DETAILS_FORM_ID) {
            return LoginEncryptionUtils.authenticateFromForm(session, connector, packet.getFormId(), packet.getFormData());
        }
        FormAddonListener.get().handleResponse(this.session, packet);
        return true;
    }

    private boolean couldLoginUserByName(String bedrockUsername) {
        if (connector.getConfig().getUserAuths() != null) {
            GeyserConfiguration.IUserAuthenticationInfo info = connector.getConfig().getUserAuths().get(bedrockUsername);

            if (info != null) {
                connector.getLogger().info(LanguageUtils.getLocaleStringLog("geyser.auth.stored_credentials", session.getAuthData().getName()));
                session.authenticate(info.getEmail(), info.getPassword());

                // TODO send a message to bedrock user telling them they are connected (if nothing like a motd
                //      somes from the Java server w/in a few seconds)
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean handle(SetLocalPlayerAsInitializedPacket packet) {
        LanguageUtils.loadGeyserLocale(session.getClientData().getLanguageCode());

        if (!session.isLoggedIn() && !session.isLoggingIn() && session.getConnector().getAuthType() == AuthType.ONLINE) {
            // TODO it is safer to key authentication on something that won't change (UUID, not username)
            if (!couldLoginUserByName(session.getAuthData().getName())) {
                LoginEncryptionUtils.showLoginWindow(session);
            }
            // else we were able to log the user in
        }
        return translateAndDefault(packet);
    }

    @Override
    public boolean handle(MovePlayerPacket packet) {
        if (session.isLoggingIn()) {
            session.sendMessage(LanguageUtils.getPlayerLocaleString("geyser.auth.login.wait", session.getClientData().getLanguageCode()));
        }

        return translateAndDefault(packet);
    }

    @Override
    boolean defaultHandler(BedrockPacket packet) {
        return translateAndDefault(packet);
    }
}
