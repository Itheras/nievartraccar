/*
 * Copyright 2017 - 2025 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.traccar.BaseProtocolEncoder;
import org.traccar.Protocol;
import org.traccar.config.Keys;
import org.traccar.helper.DataConverter;
import org.traccar.helper.model.AttributeUtil;
import org.traccar.model.Command;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

public class HuabaoProtocolEncoder extends BaseProtocolEncoder {

    private static final int TERMINAL_ID_LENGTH = 6;

    public HuabaoProtocolEncoder(Protocol protocol) {
        super(protocol);
    }

    /**
     * Builds the terminal identifier of the message header. The header field has a fixed width, so an identifier that
     * does not encode to exactly six bytes (seven for the alternative framing) has to be normalised first. Writing it
     * unchanged would either shift the message body out of the position the device reads it from, or fail outright on
     * an odd number of hex digits.
     */
    private ByteBuf encodeId(long deviceId) {
        String unique = getUniqueId(deviceId).replaceAll("[^0-9A-Fa-f]", "");
        if (unique.length() % 2 != 0) {
            unique = "0" + unique;
        }
        int length = unique.length() / 2;
        if (length != TERMINAL_ID_LENGTH && length != TERMINAL_ID_LENGTH + 1) {
            if (length > TERMINAL_ID_LENGTH) {
                unique = unique.substring(unique.length() - TERMINAL_ID_LENGTH * 2);
            } else {
                unique = "0".repeat(TERMINAL_ID_LENGTH * 2 - unique.length()) + unique;
            }
        }
        return Unpooled.wrappedBuffer(DataConverter.parseHex(unique));
    }

    private record ServerAddress(String host, int port) {
    }

    /**
     * Splits an address that carries its own port, so that a separate server and port and a combined host:port string
     * are both accepted.
     */
    private ServerAddress splitServer(String server, int port) {
        server = server.trim();
        int separator = server.lastIndexOf(':');
        if (separator > 0 && server.indexOf(':') == separator
                && server.substring(separator + 1).matches("\\d{1,5}")) {
            return new ServerAddress(
                    server.substring(0, separator), Integer.parseInt(server.substring(separator + 1)));
        }
        return new ServerAddress(server, port);
    }

    /**
     * Wraps an ASCII command such as {@code <SPGS*RBT>} into a text message. The body carries its own text length in
     * front of the content, and the message body length in the header covers that field and the encoding mark as
     * well, which is what makes these commands easy to truncate when they are assembled by hand.
     */
    private ByteBuf encodeTextMessage(ByteBuf id, String text) {
        byte[] content = text.getBytes(StandardCharsets.US_ASCII);
        ByteBuf data = Unpooled.buffer();
        data.writeByte(0x4e); // ascii encoding
        data.writeShort(content.length);
        data.writeBytes(content);
        return HuabaoProtocolDecoder.formatMessage(
                0x7e, HuabaoProtocolDecoder.MSG_SEND_TEXT_MESSAGE_2, id, false, data);
    }

    /**
     * Builds the text command that points a device at a different server. A literal address uses the command word for
     * an IP, which expects every octet padded to three digits, and anything else uses the command word for a domain.
     */
    private String formatServerCommand(String password, String server, int port) {
        if (server.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            StringBuilder address = new StringBuilder();
            for (String octet : server.split("\\.")) {
                if (address.length() > 0) {
                    address.append('.');
                }
                address.append(String.format("%03d", Integer.parseInt(octet)));
            }
            return "<SPGS*P:" + password + "*T:" + address + "," + port + ">";
        }
        return "<SPGS*P:" + password + "*Q:" + server + "," + port + ">";
    }

    /**
     * Writes the main server address and TCP port into a configuration parameters body. Unlike the proprietary
     * parameter setting message, this one identifies every parameter with a four byte id, and the declared value
     * length has to match the number of bytes actually written or the device stores a truncated address.
     */
    private void encodeServerParameters(ByteBuf data, String server, int port) {

        byte[] address = server.getBytes(StandardCharsets.US_ASCII);
        if (address.length == 0 || address.length > 255) {
            throw new IllegalArgumentException("Invalid server address length: " + address.length);
        }

        data.writeByte(2); // number of parameters
        data.writeInt(0x0013); // main server address
        data.writeByte(address.length); // parameter value length
        data.writeBytes(address);
        data.writeInt(0x0018); // server tcp port
        data.writeByte(4); // parameter value length
        data.writeInt(port);
    }

    @Override
    protected Object encodeCommand(Command command) {

        boolean alternative = AttributeUtil.lookup(
                getCacheManager(), Keys.PROTOCOL_ALTERNATIVE.withPrefix(getProtocolName()), command.getDeviceId());

        ByteBuf id = encodeId(command.getDeviceId());
        try {
            ByteBuf data = Unpooled.buffer();
            byte[] time = DataConverter.parseHex(new SimpleDateFormat("yyMMddHHmmss").format(new Date()));

            switch (command.getType()) {
                case Command.TYPE_CUSTOM:
                    String content = command.getString(Command.KEY_DATA);
                    // An ASCII command is wrapped in a text message, anything else stays a raw hex payload
                    if (content != null && content.startsWith("<")) {
                        return encodeTextMessage(id, content);
                    } else if ("gosafe".equals(getDeviceModel(command.getDeviceId()))) {
                        return Unpooled.wrappedBuffer(DataConverter.parseHex(content));
                    } else if ("BSJ".equals(getDeviceModel(command.getDeviceId()))) {
                        data.writeByte(1); // flag
                        var charset = Charset.isSupported("GBK") ? Charset.forName("GBK") : StandardCharsets.US_ASCII;
                        data.writeCharSequence(command.getString(Command.KEY_DATA), charset);
                        return HuabaoProtocolDecoder.formatMessage(
                                0x7e, HuabaoProtocolDecoder.MSG_SEND_TEXT_MESSAGE, id, false, data);
                    } else {
                        return Unpooled.wrappedBuffer(DataConverter.parseHex(command.getString(Command.KEY_DATA)));
                    }
                case Command.TYPE_REBOOT_DEVICE:
                    data.writeByte(1); // number of parameters
                    data.writeByte(0x23); // parameter id
                    data.writeByte(1); // parameter value length
                    data.writeByte(0x03); // restart
                    return HuabaoProtocolDecoder.formatMessage(
                            0x7e, HuabaoProtocolDecoder.MSG_PARAMETER_SETTING, id, false, data);
                case Command.TYPE_SET_CONNECTION:
                    ServerAddress address = splitServer(
                            command.getString(Command.KEY_SERVER), command.getInteger(Command.KEY_PORT));
                    if ("gosafe".equals(getDeviceModel(command.getDeviceId()))) {
                        initDevicePassword(command, "GSGPS");
                        return encodeTextMessage(id, formatServerCommand(
                                command.getString(Command.KEY_DEVICE_PASSWORD), address.host(), address.port()));
                    }
                    encodeServerParameters(data, address.host(), address.port());
                    return HuabaoProtocolDecoder.formatMessage(
                            0x7e, HuabaoProtocolDecoder.MSG_CONFIGURATION_PARAMETERS, id, false, data);
                case Command.TYPE_POSITION_PERIODIC:
                    data.writeByte(1); // number of parameters
                    data.writeByte(0x06); // parameter id
                    data.writeByte(4); // parameter value length
                    data.writeInt(command.getInteger(Command.KEY_FREQUENCY));
                    return HuabaoProtocolDecoder.formatMessage(
                            0x7e, HuabaoProtocolDecoder.MSG_PARAMETER_SETTING, id, false, data);
                case Command.TYPE_ALARM_ARM:
                case Command.TYPE_ALARM_DISARM:
                    data.writeByte(1); // number of parameters
                    data.writeByte(0x24); // parameter id
                    String username = "user";
                    data.writeByte(1 + username.length()); // parameter value length
                    data.writeByte(command.getType().equals(Command.TYPE_ALARM_ARM) ? 0x01 : 0x00);
                    data.writeCharSequence(username, StandardCharsets.US_ASCII);
                    return HuabaoProtocolDecoder.formatMessage(
                            0x7e, HuabaoProtocolDecoder.MSG_PARAMETER_SETTING, id, false, data);
                case Command.TYPE_ENGINE_STOP:
                case Command.TYPE_ENGINE_RESUME:
                    if (alternative) {
                        data.writeByte(command.getType().equals(Command.TYPE_ENGINE_STOP) ? 0x01 : 0x00);
                        data.writeBytes(time);
                        return HuabaoProtocolDecoder.formatMessage(
                                0x7e, HuabaoProtocolDecoder.MSG_OIL_CONTROL, id, false, data);
                    } else {
                        if ("VL300".equals(getDeviceModel(command.getDeviceId()))) {
                            data.writeCharSequence(command.getType().equals(Command.TYPE_ENGINE_STOP) ? "#0;1" : "#0;0",
                                    StandardCharsets.US_ASCII);
                        } else {
                            data.writeByte(command.getType().equals(Command.TYPE_ENGINE_STOP) ? 0xf0 : 0xf1);
                        }
                        return HuabaoProtocolDecoder.formatMessage(
                                0x7e, HuabaoProtocolDecoder.MSG_TERMINAL_CONTROL, id, false, data);
                    }
                default:
                    return null;
            }
        } finally {
            id.release();
        }
    }

}
